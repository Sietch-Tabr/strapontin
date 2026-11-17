/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.Chemins;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de {@link Installateur} sur des répertoires temporaires : releases,
 * provenance et scripts sont simulés, {@code tar} est le vrai.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class InstallateurTest {

  private static final Version CIBLE = Version.analyser("3.2.0");

  @TempDir Path dossier;

  private final List<String> scripts = new ArrayList<>();
  private final Map<String, String> environnementUpdate = new HashMap<>();
  private final Map<String, Integer> codes = new HashMap<>();
  private final List<String> verifies = new ArrayList<>();
  private final Map<String, byte[]> fichiersRelease = new HashMap<>();
  private Chemins chemins;
  private Path jars;
  private EtatMiseAJour etat;
  private boolean provenanceRefusee;
  private boolean sommeFausse;
  private boolean releaseAbsente;

  @BeforeEach
  void preparer() throws Exception {
    final Path racine = dossier.resolve("app");
    final Path var = dossier.resolve("var");
    jars = Files.createDirectories(racine.resolve("application"));
    Files.createDirectories(racine.resolve("exploitation"));
    Files.createDirectories(var.resolve("etat"));
    chemins = new Chemins(Map.of("APP_RACINE", racine.toString(), "VAR_RACINE", var.toString()));
    etat = new EtatMiseAJour(chemins.etatDir(), Clock.systemUTC());
    VersionInstalleeTest.jar(jars.resolve("web.jar"), "build.version=3.1.5\n");
    Files.writeString(jars.resolve("worker.jar"), "ancien worker");
    fichiersRelease.put(
        "web.jar",
        Files.readAllBytes(
            VersionInstalleeTest.jar(dossier.resolve("nouveau.jar"), "build.version=3.2.0\n")));
    fichiersRelease.put("worker.jar", "nouveau worker".getBytes());
    fichiersRelease.put("mise-a-jour.jar", "nouvel installateur".getBytes());
    final String script = "#!/bin/bash\necho ok\n";
    fichiersRelease.put(
        "exploitation.tar.gz",
        Files.readAllBytes(
            ArchiveExploitationTest.archive(
                Files.createDirectory(dossier.resolve("archive")),
                script,
                ArchiveExploitationTest.somme(script),
                null)));
  }

  private static String somme(final byte[] contenu) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contenu));
  }

  private SourceReleases source() {
    return new SourceReleases() {
      @Override
      public Optional<ReleaseGithub> release(final Version version) {
        if (releaseAbsente) {
          return Optional.empty();
        }
        final Map<String, Asset> assets = new HashMap<>();
        for (final String nom :
            List.of(
                "SHA256SUMS", "web.jar", "worker.jar", "mise-a-jour.jar", "exploitation.tar.gz")) {
          if (!nom.equals("worker.jar") || !fichiersRelease.containsKey("sans-worker")) {
            assets.put(nom, new Asset(nom, URI.create("https://github.com/x/" + nom), 1));
          }
        }
        return Optional.of(new ReleaseGithub("v" + version, version, false, false, assets));
      }

      @Override
      public Telechargement telecharger(final Asset asset, final Path destination) {
        try {
          final byte[] contenu = contenu(asset.nom());
          final Path fichier = destination.resolve(asset.nom());
          Files.write(fichier, contenu);
          return new Telechargement(fichier, somme(contenu), contenu.length);
        } catch (final Exception e) {
          throw new IllegalStateException(e);
        }
      }
    };
  }

  private byte[] contenu(final String nom) throws Exception {
    if (!"SHA256SUMS".equals(nom)) {
      return fichiersRelease.get(nom);
    }
    final StringBuilder sommes = new StringBuilder();
    for (final String fichier :
        List.of("web.jar", "worker.jar", "mise-a-jour.jar", "exploitation.tar.gz")) {
      final String valeur =
          sommeFausse && "web.jar".equals(fichier)
              ? "0".repeat(64)
              : somme(fichiersRelease.get(fichier));
      sommes.append(valeur).append("  ").append(fichier).append('\n');
    }
    return sommes.toString().getBytes();
  }

  private ExecuteurCommande executeur() {
    final ExecuteurCommande reel = new ExecuteurProcessus();
    return (commande, environnement) -> {
      if ("tar".equals(commande.get(0))) {
        return reel.executer(commande, environnement);
      }
      final String script = Path.of(commande.get(1)).getFileName().toString();
      scripts.add(script);
      if ("update.sh".equals(script)) {
        environnementUpdate.putAll(environnement);
      }
      final int code = codes.getOrDefault(script, 0);
      if (code == 0 && "update.sh".equals(script)) {
        try {
          Files.move(
              jars.resolve("web.jar.new"),
              jars.resolve("web.jar"),
              StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
          throw new IllegalStateException(e);
        }
      }
      return new SortieCommande(code, "");
    };
  }

  private Installateur installateur() {
    final VerificateurProvenance provenance =
        (fichier, identite) -> {
          if (provenanceRefusee) {
            throw new ProvenanceException("refusée");
          }
          verifies.add(fichier.getFileName().toString());
          assertEquals("refs/tags/v3.2.0", identite.reference());
        };
    return new Installateur(
        source(), provenance, executeur(), chemins, "Sietch-Tabr/strapontin", etat);
  }

  @Test
  void installer_releaseValide_installeLaVersionDansLOrdre() throws Exception {
    installateur().installer(CIBLE);
    assertEquals(CIBLE, VersionInstallee.lire(jars.resolve("web.jar")));
    assertEquals(List.of("backup-local.sh", "backup.sh", "update.sh"), scripts);
    assertEquals("1", environnementUpdate.get("VERROU_HERITE"));
    assertEquals(
        List.of("web.jar", "worker.jar", "mise-a-jour.jar", "exploitation.tar.gz"), verifies);
    assertEquals("nouveau worker", Files.readString(jars.resolve("worker.jar.new")));
    assertTrue(Files.exists(jars.resolve("mise-a-jour.jar.new")));
    assertTrue(Files.exists(chemins.appExploitation().resolve("MANIFESTE.sha256")));
    assertTrue(Files.readString(chemins.etatDir().resolve("mise-a-jour.json")).contains("REUSSI"));
    assertFalse(Files.exists(chemins.telechargementsDir().resolve("3.2.0")));
  }

  @Test
  void installer_versionNonPosterieure_refuseSansRienTelecharger() {
    final Installateur installateur = installateur();
    final Version actuelle = Version.analyser("3.1.5");
    assertThrows(InstallationException.class, () -> installateur.installer(actuelle));
    assertTrue(scripts.isEmpty());
    assertTrue(verifies.isEmpty());
  }

  @Test
  void installer_releaseIntrouvable_leveUneException() {
    releaseAbsente = true;
    final Installateur installateur = installateur();
    assertThrows(InstallationException.class, () -> installateur.installer(CIBLE));
  }

  @Test
  void installer_fichierAbsentDeLaRelease_leveUneException() {
    fichiersRelease.put("sans-worker", new byte[0]);
    final Installateur installateur = installateur();
    assertThrows(InstallationException.class, () -> installateur.installer(CIBLE));
    assertTrue(scripts.isEmpty());
  }

  @Test
  void installer_sommeDifferente_arreteAvantLaSauvegarde() {
    sommeFausse = true;
    final Installateur installateur = installateur();
    assertThrows(IntegriteException.class, () -> installateur.installer(CIBLE));
    assertTrue(scripts.isEmpty());
    assertFalse(Files.exists(chemins.telechargementsDir().resolve("3.2.0")));
  }

  @Test
  void installer_provenanceRefusee_arreteAvantLaSauvegarde() throws Exception {
    provenanceRefusee = true;
    final Installateur installateur = installateur();
    assertThrows(ProvenanceException.class, () -> installateur.installer(CIBLE));
    assertTrue(scripts.isEmpty());
    assertEquals(Version.analyser("3.1.5"), VersionInstallee.lire(jars.resolve("web.jar")));
    assertFalse(Files.exists(jars.resolve("web.jar.new")));
  }

  @Test
  void installer_sauvegardeLocaleEnEchec_abandonneAvantTouteInstallation() {
    codes.put("backup-local.sh", 1);
    final Installateur installateur = installateur();
    assertThrows(InstallationException.class, () -> installateur.installer(CIBLE));
    assertEquals(List.of("backup-local.sh"), scripts);
    assertFalse(Files.exists(jars.resolve("web.jar.new")));
  }

  @Test
  void installer_sauvegardeNasEnEchec_continue() throws Exception {
    codes.put("backup.sh", 1);
    installateur().installer(CIBLE);
    assertEquals(CIBLE, VersionInstallee.lire(jars.resolve("web.jar")));
  }

  @Test
  void installer_updateEnEchec_leveUneException() throws Exception {
    codes.put("update.sh", 1);
    final Installateur installateur = installateur();
    assertThrows(InstallationException.class, () -> installateur.installer(CIBLE));
    assertEquals(Version.analyser("3.1.5"), VersionInstallee.lire(jars.resolve("web.jar")));
  }
}
