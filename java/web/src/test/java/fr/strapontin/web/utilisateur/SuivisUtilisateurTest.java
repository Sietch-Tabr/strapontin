/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SuivisUtilisateurTest {

  @Test
  void nombreTotal_additionneArtistesEtSalles() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForObject(contains("user_artistes"), anyMap(), eq(Integer.class)))
        .thenReturn(3);
    when(jdbc.queryForObject(contains("user_salles"), anyMap(), eq(Integer.class))).thenReturn(4);

    assertEquals(7, SuivisUtilisateur.nombreTotal(jdbc, "u1"));
  }

  @Test
  void nombreArtistes_resultatNul_retourneZero() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    assertEquals(0, SuivisUtilisateur.nombreArtistes(jdbc, "u1"));
  }

  // Le décompte des suivis n'a qu'une définition : un appelant qui le recopie fait échouer le test.
  @Test
  void decompteDesSuivis_n_estDefiniQueDansSuivisUtilisateur() throws IOException {
    final Pattern decompte =
        Pattern.compile("COUNT\\(\\*\\)\\s+FROM\\s+user_(artistes|salles)\\s+WHERE\\s+user_id");
    final List<String> copies = new ArrayList<>();
    try (Stream<Path> fichiers = Files.walk(Path.of("src/main/java"))) {
      for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
        if (!fichier.getFileName().toString().equals("SuivisUtilisateur.java")
            && decompte.matcher(Files.readString(fichier)).find()) {
          copies.add(fichier.getFileName().toString());
        }
      }
    }
    assertTrue(copies.isEmpty(), "Décompte des suivis recopié dans : " + copies);
  }
}
