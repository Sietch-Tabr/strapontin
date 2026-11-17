/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Chemins;
import fr.strapontin.exploitation.Verrou;
import fr.strapontin.exploitation.VerrouOccupeException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

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
    final EtatMiseAJour etat = new EtatMiseAJour(chemins.etatDir(), Clock.systemUTC());
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
      code = executer(chemins, installateur, etat);
    }
    if (code != 0) {
      // L'exception non rattrapée donne la sortie 1 à systemd.
      throw new InstallationException("Mise à jour en échec : voir le journal et l'état");
    }
  }

  /**
   * Traite la plus ancienne demande en attente.
   *
   * @param chemins emplacements de l'installation
   * @param installateur installateur de la release
   * @param etat état écrit pour l'administration
   * @return le code de sortie du processus
   */
  /* package-private : les tests le lancent sans réseau ni systemd. */
  static int executer(
      final Chemins chemins, final Installateur installateur, final EtatMiseAJour etat) {
    final Path demande;
    try {
      demande = premiereDemande(chemins.demandesDir());
    } catch (final IOException e) {
      JOURNAL.log(System.Logger.Level.ERROR, "Dossier des demandes illisible", e);
      return 1;
    }
    if (demande == null) {
      return 0;
    }
    try {
      // Retirée d'abord : jamais rejouée, même si le verrou est pris.
      final Demande lue = lireEtRetirer(demande);
      try (Verrou verrou = Verrou.prendre(chemins.verrouDir(), "mise-a-jour")) {
        if (verrou.reprisApresPerime()) {
          JOURNAL.log(System.Logger.Level.WARNING, "Verrou périmé écarté");
        }
        installateur.installer(lue.version());
      }
      JOURNAL.log(System.Logger.Level.INFO, "Version {0} installée", lue.version());
      return 0;
    } catch (final VerrouOccupeException e) {
      return echec(etat, "Une autre opération d'exploitation est en cours", e);
    } catch (final InstallationException
        | ProvenanceException
        | IntegriteException
        | SourceInaccessibleException e) {
      return echec(etat, e.getMessage(), e);
    }
  }

  private static Demande lireEtRetirer(final Path demande) {
    try {
      return Demande.lire(demande);
    } finally {
      try {
        Files.deleteIfExists(demande);
      } catch (final IOException e) {
        JOURNAL.log(System.Logger.Level.ERROR, "Demande non supprimée", e);
      }
    }
  }

  private static int echec(final EtatMiseAJour etat, final String message, final Exception cause) {
    JOURNAL.log(System.Logger.Level.ERROR, message, cause);
    etat.echouer(message);
    return 1;
  }

  private static Path premiereDemande(final Path dossier) throws IOException {
    if (!Files.isDirectory(dossier)) {
      return null;
    }
    try (Stream<Path> contenu = Files.list(dossier)) {
      final List<Path> demandes =
          contenu
              .filter(chemin -> chemin.getFileName().toString().endsWith(".json"))
              .filter(Files::isRegularFile)
              .sorted()
              .toList();
      return demandes.isEmpty() ? null : demandes.get(0);
    }
  }
}
