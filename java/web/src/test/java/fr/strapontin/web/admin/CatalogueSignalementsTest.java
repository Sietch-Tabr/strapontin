/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Chaque type de signalement émis par le worker (appels {@code signaler("type", …)} et invariants
 * {@code publier("type", …)}) a un nom et une explication dans {@link CatalogueSignalements} : sans
 * eux, la ligne s'afficherait sous son identifiant technique dans l'onglet « À traiter ».
 *
 * @since 3.0.2
 * @author Kwaite
 */
class CatalogueSignalementsTest {

  private static final Pattern EMISSION =
      Pattern.compile("(?:\\.signaler|publier)\\(\\s*\"([a-z_]+)\"");

  @Test
  void catalogue_toutTypeEmisParLeWorker_estDecrit() throws IOException {
    final Set<String> emis = new TreeSet<>();
    try (Stream<Path> fichiers = Files.walk(Path.of("../worker/src/main/java"))) {
      for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
        final Matcher m = EMISSION.matcher(Files.readString(fichier));
        while (m.find()) {
          emis.add(m.group(1));
        }
      }
    }

    assertEquals(
        List.of(),
        emis.stream().filter(type -> !CatalogueSignalements.estDecrit(type)).toList(),
        "types de signalement sans description");
    assertEquals(true, emis.size() > 10, "analyse des sources du worker inopérante");
  }
}
