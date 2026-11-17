/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import fr.strapontin.exploitation.Chemins;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Installation d'une release : contrôles, sauvegarde, dépôt des fichiers
 * puis {@code update.sh}, qui redémarre les services (retour arrière au
 * besoin) et met en place {@code mise-a-jour.jar.new}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
public final class Installateur {

  private static final System.Logger JOURNAL = System.getLogger("miseajour");
  private static final String WEB = "web.jar";
  private static final String WORKER = "worker.jar";
  private static final String MISE_A_JOUR = "mise-a-jour.jar";
  private static final String EXPLOITATION = "exploitation.tar.gz";
  private static final String SOMMES = "SHA256SUMS";
  private static final List<String> FICHIERS_CONTROLES =
      List.of(WEB, WORKER, MISE_A_JOUR, EXPLOITATION);

  private final SourceReleases source;
  private final VerificateurProvenance provenance;
  private final ExecuteurCommande executeur;
  private final Chemins chemins;
  private final String depot;
  private final EtatMiseAJour etat;

  /**
   * @param source origine des releases
   * @param provenance vérificateur de provenance des fichiers
   * @param executeur lanceur de programmes du système
   * @param chemins emplacements de l'installation
   * @param depot dépôt {@code propriétaire/nom} dont viennent les releases
   * @param etat état écrit pour l'administration
   */
  public Installateur(
      final SourceReleases source,
      final VerificateurProvenance provenance,
      final ExecuteurCommande executeur,
      final Chemins chemins,
      final String depot,
      final EtatMiseAJour etat) {
    this.source = source;
    this.provenance = provenance;
    this.executeur = executeur;
    this.chemins = chemins;
    this.depot = depot;
    this.etat = etat;
  }

  /**
   * Installe une version. L'appelant tient le verrou d'exploitation.
   *
   * @param cible version à installer, strictement supérieure à l'installée
   * @throws InstallationException si une étape échoue : l'état porte alors
   *     l'étape et le motif, et les services restent sur la version installée
   */
  public void installer(final Version cible) {
    etat.demarrer(cible);
    final Version installee = VersionInstallee.lire(chemins.appJars().resolve(WEB));
    if (cible.compareTo(installee) <= 0) {
      throw new InstallationException(
          "La version " + cible + " n'est pas postérieure à la version installée " + installee);
    }
    final ReleaseGithub release = release(cible);
    final Path dossier = preparer(chemins.telechargementsDir().resolve(cible.toString()));
    try {
      final Map<String, Telechargement> fichiers = telecharger(release, dossier);
      controler(fichiers, cible);
      sauvegarder();
      extraire(fichiers.get(EXPLOITATION), dossier);
      deposerJars(fichiers);
      lancerUpdate();
      verifierVersion(cible);
      etat.reussir();
    } finally {
      nettoyerSansEchec(dossier);
    }
  }

  private ReleaseGithub release(final Version cible) {
    final ReleaseGithub release =
        source
            .release(cible)
            .orElseThrow(() -> new InstallationException("Release introuvable : v" + cible));
    if (release.brouillon()) {
      throw new InstallationException("La release v" + cible + " n'est pas publiée");
    }
    return release;
  }

  private Map<String, Telechargement> telecharger(final ReleaseGithub release, final Path dossier) {
    etat.etape(Etape.TELECHARGEMENT);
    final Map<String, Telechargement> fichiers = new LinkedHashMap<>();
    for (final String nom : List.of(SOMMES, WEB, WORKER, MISE_A_JOUR, EXPLOITATION)) {
      final Asset asset = release.assets().get(nom);
      if (asset == null) {
        throw new InstallationException("Fichier absent de la release : " + nom);
      }
      fichiers.put(nom, source.telecharger(asset, dossier));
    }
    return fichiers;
  }

  private void controler(final Map<String, Telechargement> fichiers, final Version cible) {
    etat.etape(Etape.PROVENANCE);
    final SommesControle sommes;
    try {
      sommes = SommesControle.analyser(Files.readString(fichiers.get(SOMMES).fichier()));
    } catch (final IOException e) {
      throw new InstallationException("SHA256SUMS illisible", e);
    }
    final IdentiteAttendue identite = IdentiteAttendue.pourRelease(depot, cible);
    for (final String nom : FICHIERS_CONTROLES) {
      sommes.verifier(fichiers.get(nom));
      provenance.verifier(fichiers.get(nom).fichier(), identite);
    }
  }

  private void sauvegarder() {
    etat.etape(Etape.SAUVEGARDE);
    final Path scripts = chemins.appExploitation();
    exiger(
        executeur.executer(
            List.of("bash", scripts.resolve("backup-local.sh").toString()), Map.of()),
        "Sauvegarde locale impossible : mise à jour abandonnée");
    // Comme /deploiement-prod : le NAS est en plus, sa panne n'arrête rien.
    executeur.executer(List.of("bash", scripts.resolve("backup.sh").toString()), Map.of());
  }

  private void extraire(final Telechargement archive, final Path dossier) {
    etat.etape(Etape.EXTRACTION);
    final Path extraction = dossier.resolve("extraction");
    try {
      Files.createDirectory(
          extraction,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (final IOException e) {
      throw new InstallationException("Dossier d'extraction impossible", e);
    }
    final ArchiveExploitation exploitation = new ArchiveExploitation(executeur);
    final Map<String, String> manifeste = exploitation.extraire(archive.fichier(), extraction);
    exploitation.deposer(extraction.resolve("exploitation"), manifeste, chemins.appExploitation());
  }

  private void deposerJars(final Map<String, Telechargement> fichiers) {
    etat.etape(Etape.INSTALLATION);
    try {
      for (final String nom : List.of(WEB, WORKER, MISE_A_JOUR)) {
        final Path cible = chemins.appJars().resolve(nom + ".new");
        Files.copy(fichiers.get(nom).fichier(), cible, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(cible, PosixFilePermissions.fromString("rw-r--r--"));
      }
    } catch (final IOException e) {
      throw new InstallationException("Dépôt des JARs impossible", e);
    }
  }

  private void lancerUpdate() {
    final Path update = chemins.appExploitation().resolve("update.sh");
    exiger(
        executeur.executer(List.of("bash", update.toString()), Map.of("VERROU_HERITE", "1")),
        "Le déploiement a échoué : retour automatique à la version précédente");
  }

  private void verifierVersion(final Version cible) {
    etat.etape(Etape.CONTROLE);
    final Version relue = VersionInstallee.lire(chemins.appJars().resolve(WEB));
    if (!relue.equals(cible)) {
      throw new InstallationException(
          "Version relue " + relue + " différente de la version demandée " + cible);
    }
  }

  private static void exiger(final SortieCommande sortie, final String message) {
    if (sortie.code() != 0) {
      throw new InstallationException(message);
    }
  }

  private static Path preparer(final Path dossier) {
    try {
      nettoyer(dossier);
      Files.createDirectories(dossier.getParent());
      return Files.createDirectory(
          dossier,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (final IOException e) {
      throw new InstallationException("Dossier de téléchargement impossible", e);
    }
  }

  private static void nettoyer(final Path dossier) throws IOException {
    if (!Files.exists(dossier)) {
      return;
    }
    try (Stream<Path> contenu = Files.walk(dossier)) {
      for (final Path chemin : contenu.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(chemin);
      }
    }
  }

  private static void nettoyerSansEchec(final Path dossier) {
    try {
      nettoyer(dossier);
    } catch (final IOException | UncheckedIOException e) {
      JOURNAL.log(System.Logger.Level.WARNING, "Téléchargement non effacé", e);
    }
  }
}
