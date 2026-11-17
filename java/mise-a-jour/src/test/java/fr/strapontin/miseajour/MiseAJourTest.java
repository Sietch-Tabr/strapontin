/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.exploitation.Chemins;
import fr.strapontin.exploitation.Verrou;
import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de {@link MiseAJour#executer} : demande, verrou et état, sans réseau.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class MiseAJourTest {

  private static final String DEMANDE =
      "{\"version\":\"3.2.0\",\"demandeur\":\"kwaite\",\"horodatage\":\"2026-11-17T10:00:00Z\"}";

  @TempDir Path dossier;

  private Chemins chemins;
  private EtatMiseAJour etat;
  private Installateur installateur;

  @BeforeEach
  void preparer() throws IOException {
    final Path racine = dossier.resolve("app");
    final Path var = dossier.resolve("var");
    final Path jars = Files.createDirectories(racine.resolve("application"));
    Files.createDirectories(var.resolve("etat"));
    Files.createDirectories(var.resolve("demandes"));
    VersionInstalleeTest.jar(jars.resolve("web.jar"), "build.version=3.1.5\n");
    chemins = new Chemins(Map.of("APP_RACINE", racine.toString(), "VAR_RACINE", var.toString()));
    etat = new EtatMiseAJour(chemins.etatDir(), Clock.systemUTC());
    final SourceReleases sansRelease =
        new SourceReleases() {
          @Override
          public Optional<ReleaseGithub> release(final Version version) {
            return Optional.empty();
          }

          @Override
          public Telechargement telecharger(final Asset asset, final Path destination) {
            throw new IllegalStateException("aucun téléchargement attendu");
          }
        };
    installateur =
        new Installateur(
            sansRelease,
            (fichier, identite) -> {},
            (commande, environnement) -> new SortieCommande(0, ""),
            chemins,
            "Sietch-Tabr/strapontin",
            etat);
  }

  private Path deposer(final String contenu) throws IOException {
    return Files.writeString(chemins.demandesDir().resolve("demande.json"), contenu);
  }

  private String etatLu() throws IOException {
    return Files.readString(chemins.etatDir().resolve(EtatMiseAJour.FICHIER));
  }

  @Test
  void executer_sansDemande_neFaitRien() {
    assertEquals(0, MiseAJour.executer(chemins, installateur, etat));
    assertFalse(Files.exists(chemins.etatDir().resolve(EtatMiseAJour.FICHIER)));
  }

  @Test
  void executer_installationEnEchec_ecritLEtatEtRetireLaDemande() throws IOException {
    final Path demande = deposer(DEMANDE);
    assertEquals(1, MiseAJour.executer(chemins, installateur, etat));
    assertFalse(Files.exists(demande));
    assertTrue(etatLu().contains("ECHEC"));
    assertTrue(etatLu().contains("Release introuvable"));
    assertFalse(Files.exists(chemins.verrouDir()));
  }

  @Test
  void executer_demandeIllisible_ecritUnEchecEtLaRetire() throws IOException {
    final Path demande = deposer("pas du json");
    assertEquals(1, MiseAJour.executer(chemins, installateur, etat));
    assertFalse(Files.exists(demande));
    assertTrue(etatLu().contains("ECHEC"));
  }

  @Test
  void executer_verrouPris_ecritUnEchecEtRetireLaDemande() throws IOException {
    final Path demande = deposer(DEMANDE);
    try (Verrou autre = Verrou.prendre(chemins.verrouDir(), "backup")) {
      assertEquals(1, MiseAJour.executer(chemins, installateur, etat));
      assertTrue(etatLu().contains("autre opération"));
    }
    assertFalse(Files.exists(demande));
  }
}
