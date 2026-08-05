/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Garde-fou : un seul conteneur Testcontainers PostgreSQL doit exister pour tout le
/// module web ({@link AbstractWebIntegrationTest}), jamais un de plus — l'agent CI Woodpecker
/// (1 Go RAM) a déjà été tué (`Killed`, OOM) à deux reprises (2.7.3 côté worker, 2.7.5 côté web)
/// pour cette exacte raison. Échoue si une classe de test déclare son propre `new
/// PostgreSQLContainer(...)` au lieu d'étendre la base partagée.
///
/// @since 2.7.5
/// @author Kwaite
class ConventionTestcontainersTest {

  @Test
  void aucuneClasse_neDeclareSonPropreConteneurPostgres_horsBasePartagee() throws IOException {
    Path racineTests = Path.of("src/test/java");
    List<Path> contrevenants;
    try (Stream<Path> fichiers = Files.walk(racineTests)) {
      contrevenants =
          fichiers
              .filter(p -> p.toString().endsWith(".java"))
              .filter(
                  p ->
                      !List.of(
                              "AbstractWebIntegrationTest.java",
                              "ConventionTestcontainersTest.java")
                          .contains(p.getFileName().toString()))
              .filter(ConventionTestcontainersTest::declareSonPropreConteneur)
              .toList();
    }

    assertTrue(
        contrevenants.isEmpty(),
        "Ces classes déclarent leur propre PostgreSQLContainer au lieu d'étendre"
            + " AbstractWebIntegrationTest : "
            + contrevenants);
  }

  private static boolean declareSonPropreConteneur(Path fichier) {
    try {
      return Files.readString(fichier).contains("new PostgreSQLContainer(");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
