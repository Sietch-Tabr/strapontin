/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.miseajour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.strapontin.exploitation.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires pour {@link Demande}.
 *
 * @since 3.2.0
 * @author Kwaite
 */
class DemandeTest {

  @TempDir Path dossier;

  private Path ecrire(final String json) throws IOException {
    return Files.writeString(dossier.resolve("demande.json"), json);
  }

  @Test
  void lire_demandeValide_retourneSesChamps() throws IOException {
    final Demande demande =
        Demande.lire(
            ecrire(
                "{\"version\":\"3.2.0\",\"demandeur\":\"kwaite\","
                    + "\"horodatage\":\"2026-11-17T10:00:00Z\"}"));
    assertEquals(Version.analyser("3.2.0"), demande.version());
    assertEquals("kwaite", demande.demandeur());
    assertEquals(Instant.parse("2026-11-17T10:00:00Z"), demande.horodatage());
  }

  @Test
  void lire_versionInvalide_leveUneException() throws IOException {
    final Path fichier =
        ecrire("{\"version\":\"abc\",\"demandeur\":\"k\",\"horodatage\":\"2026-11-17T10:00:00Z\"}");
    assertThrows(InstallationException.class, () -> Demande.lire(fichier));
  }

  @Test
  void lire_demandeurAvecCaractereDeControle_leveUneException() throws IOException {
    final Path fichier =
        ecrire(
            "{\"version\":\"3.2.0\",\"demandeur\":\"a\\nb\","
                + "\"horodatage\":\"2026-11-17T10:00:00Z\"}");
    assertThrows(InstallationException.class, () -> Demande.lire(fichier));
  }

  @Test
  void lire_champAbsent_leveUneException() throws IOException {
    final Path fichier = ecrire("{\"version\":\"3.2.0\"}");
    assertThrows(InstallationException.class, () -> Demande.lire(fichier));
  }

  @Test
  void lire_horodatageInvalide_leveUneException() throws IOException {
    final Path fichier =
        ecrire("{\"version\":\"3.2.0\",\"demandeur\":\"k\",\"horodatage\":\"hier\"}");
    assertThrows(InstallationException.class, () -> Demande.lire(fichier));
  }

  @Test
  void lire_fichierTropVolumineux_leveUneException() throws IOException {
    final Path fichier = ecrire("{\"x\":\"" + "a".repeat(5000) + "\"}");
    assertThrows(InstallationException.class, () -> Demande.lire(fichier));
  }

  @Test
  void lire_fichierAbsent_leveUneException() {
    final Path absent = dossier.resolve("absent.json");
    assertThrows(InstallationException.class, () -> Demande.lire(absent));
  }
}
