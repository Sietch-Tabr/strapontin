/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tout endpoint interne (sans session, appelé par les scripts du serveur) passe par le contrôle du
 * jeton commun : un futur endpoint qui recopierait ou oublierait ce contrôle fait échouer le test.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class EndpointsInternesProtegesTest {

  private static final Path RACINE = Path.of("src/main/java/fr/strapontin/web");
  // Les chemins cités par la configuration de sécurité ne sont pas des endpoints.
  private static final Pattern MAPPING_INTERNE =
      Pattern.compile("@(Post|Get|Put|Delete|Request)Mapping\\(\"/api/interne/");

  @Test
  void endpointsInternes_utilisentTousLeJetonInterne() throws IOException {
    final List<String> sansControle = new ArrayList<>();
    try (Stream<Path> fichiers = Files.walk(RACINE)) {
      for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
        final String source = Files.readString(fichier);
        if (MAPPING_INTERNE.matcher(source).find()
            && !source.contains("JetonInterne.estValide(")
            && !fichier.getFileName().toString().equals("JetonInterne.java")) {
          sansControle.add(fichier.getFileName().toString());
        }
      }
    }
    assertTrue(
        sansControle.isEmpty(),
        "Endpoints internes sans le contrôle du jeton commun : " + sansControle);
  }
}
