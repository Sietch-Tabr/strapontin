/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Les règles de sécurité et de rapprochement, l'identité du robot et les adresses de services
 * externes n'ont qu'une déclaration : une valeur redéclarée dans une autre classe, qui pourrait
 * diverger de la première sans erreur, fait échouer le test.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class ReglesDefiniesUneFoisTest {

  private static final List<Path> RACINES =
      List.of(
          Path.of("../core/src/main/java"),
          Path.of("../sources/src/main/java"),
          Path.of("src/main/java"),
          Path.of("../worker/src/main/java"));

  // Valeur littérale -> seul fichier autorisé à la porter.
  private static final Map<String, String> VALEURS_UNIQUES =
      Map.of(
          "StrapontinBot/1.0", "ConstantesSources.java",
          "https://app.ticketmaster.com/discovery/v2", "ConstantesSources.java",
          "https://api.deezer.com", "ConstantesSources.java",
          "LONGUEUR_MIN_MOT_DE_PASSE = ", "ReglesCommunes.java",
          "TOLERANCE_HEURE_MINUTES = ", "ReglesCommunes.java",
          "ECART_MAX_HEURE_SECONDES = ", "ReglesCommunes.java",
          "RAYON_MEME_LIEU_METRES = ", "ReglesCommunes.java");

  @Test
  void valeursPartagees_sontDeclareesUneSeuleFois() throws IOException {
    final List<String> copies = new ArrayList<>();
    for (final Path racine : RACINES) {
      try (Stream<Path> fichiers = Files.walk(racine)) {
        for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
          final String source = Files.readString(fichier);
          final String nom = fichier.getFileName().toString();
          for (final Map.Entry<String, String> valeur : VALEURS_UNIQUES.entrySet()) {
            if (source.contains(valeur.getKey()) && !nom.equals(valeur.getValue())) {
              copies.add(nom + " : " + valeur.getKey());
            }
          }
        }
      }
    }
    assertTrue(copies.isEmpty(), "Valeurs redéclarées hors de leur définition : " + copies);
  }

  // Seul chemin de lecture et d'écriture de app_config : la rotation du secret, qui réécrit toute
  // la table, est la seule autre classe à l'interroger.
  @Test
  void tableAppConfig_estAccedeeParAccesAppConfigSeulement() throws IOException {
    final Pattern acces = Pattern.compile("(FROM|INTO|UPDATE)\\s+app_config");
    final List<String> copies = new ArrayList<>();
    for (final Path racine : RACINES) {
      try (Stream<Path> fichiers = Files.walk(racine)) {
        for (final Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
          final String nom = fichier.getFileName().toString();
          if (!nom.equals("AccesAppConfig.java")
              && !nom.equals("RechiffrementSecretConfig.java")
              && acces.matcher(Files.readString(fichier)).find()) {
            copies.add(nom);
          }
        }
      }
    }
    assertTrue(copies.isEmpty(), "Accès SQL à app_config hors AccesAppConfig : " + copies);
  }
}
