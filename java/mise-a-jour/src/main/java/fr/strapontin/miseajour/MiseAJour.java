/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Chemins;
import fr.strapontin.exploitation.Demande;
import fr.strapontin.exploitation.FichierEnvironnement;
import fr.strapontin.exploitation.Verrou;
import fr.strapontin.exploitation.VerrouOccupeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

/**
 * Point d'entrée lancé par systemd quand le web dépose une demande : retire
 * la demande, prend le verrou, installe la version et écrit l'état.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class MiseAJour {

  private static final System.Logger JOURNAL = System.getLogger("miseajour");
  private static final String DEPOT_DEFAUT = "Sietch-Tabr/strapontin";
  private static final String VERSION_INCONNUE = "inconnue";
  private static final String AGENT = "strapontin-mise-a-jour";

  private MiseAJour() {}

  /**
   * @param arguments inutilisés : tout vient de l'environnement et de la demande
   * @throws InstallationException si la mise à jour échoue, pour sortir en code 1
   */
  public static void main(final String[] arguments) {
    final Chemins chemins = Chemins.depuisProcessus();
    final String depot = System.getenv().getOrDefault("MISE_A_JOUR_DEPOT", DEPOT_DEFAUT);
    final ConfigurationSource configuration = ConfigurationSource.github(depot, AGENT);
    final SuiviEtat etat = new SuiviEtat(chemins.etatDir(), Clock.systemUTC());
    final int code;
    try (HttpClient http = SourceGithub.clientSansRedirection()) {
      final VerificateurProvenance provenance =
          VerificateurSigstore.production(
              new AttestationsGithub(http, configuration), chemins.sigstoreDir());
      final Installateur installateur =
          new Installateur(
              new SourceGithub(http, configuration),
              provenance,
              new ExecuteurProcessus(),
              chemins,
              depot,
              etat);
      final Rapporteur rapporteur =
          new RapporteurHttp(
              http,
              URI.create(
                  "http://localhost:" + chemins.portWeb() + "/api/interne/mise-a-jour-statut"),
              FichierEnvironnement.valeur(chemins.envFichier(), "UPDATE_INTERNAL_SECRET")
                  .orElse(""));
      code = executer(chemins, installateur, etat, rapporteur);
    }
    if (code != 0) {
      // L'exception non rattrapée donne la sortie 1 à systemd.
      throw new InstallationException("Mise à jour en échec : voir le journal et l'état");
    }
  }

  /**
   * Traite la plus ancienne demande ; rend le code de sortie du processus.
   *
   * @param chemins emplacements de l'installation
   * @param installateur installateur de la release
   * @param etat état écrit pour l'administration
   * @param rapporteur compte rendu envoyé à l'application
   */
  /* package-private : les tests le lancent sans réseau ni systemd. */
  static int executer(
      final Chemins chemins,
      final Installateur installateur,
      final SuiviEtat etat,
      final Rapporteur rapporteur) {
    final Optional<Path> attente;
    try {
      attente = Demande.plusAncienne(chemins.demandesDir());
    } catch (final UncheckedIOException e) {
      JOURNAL.log(System.Logger.Level.ERROR, "Dossier des demandes illisible", e);
      return 1;
    }
    if (attente.isEmpty()) {
      return 0;
    }
    String version = VERSION_INCONNUE;
    try {
      // Retirée d'abord : jamais rejouée, même si le verrou est pris.
      final Demande lue = lireEtRetirer(attente.get());
      version = lue.version().toString();
      try (Verrou verrou = Verrou.prendre(chemins.verrouDir(), "mise-a-jour")) {
        if (verrou.reprisApresPerime()) {
          JOURNAL.log(System.Logger.Level.WARNING, "Verrou périmé écarté");
        }
        installateur.installer(lue.version());
      }
      JOURNAL.log(System.Logger.Level.INFO, "Version {0} installée", lue.version());
      rapporteur.envoyer(true, version, "Version " + version + " installée.");
      return 0;
    } catch (final VerrouOccupeException e) {
      return echec(etat, rapporteur, version, "Une autre opération d'exploitation est en cours", e);
    } catch (final InstallationException
        | ProvenanceException
        | IntegriteException
        | SourceInaccessibleException e) {
      return echec(etat, rapporteur, version, e.getMessage(), e);
    }
  }

  private static Demande lireEtRetirer(final Path demande) {
    try {
      return Demande.lire(demande);
    } catch (final IllegalArgumentException | UncheckedIOException e) {
      throw new InstallationException("Demande de mise à jour illisible", e);
    } finally {
      try {
        Files.deleteIfExists(demande);
      } catch (final IOException e) {
        JOURNAL.log(System.Logger.Level.ERROR, "Demande non supprimée", e);
      }
    }
  }

  private static int echec(
      final SuiviEtat etat,
      final Rapporteur rapporteur,
      final String version,
      final String message,
      final Exception cause) {
    JOURNAL.log(System.Logger.Level.ERROR, message, cause);
    etat.echouer(message);
    rapporteur.envoyer(false, version, message);
    return 1;
  }
}
