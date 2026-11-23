/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Banc de mesure rejouable de la correspondance floue sur des paires de noms réels : coût d'un appel
 * de {@link CorrespondanceSalle#estDoublonSalle(String, String)} et de {@link
 * CorrespondanceArtiste#estDoublonArtiste(String, String)} sur toutes les paires d'un corpus (salles
 * et artistes de la copie de production du dev), nombre de doublons trouvés, JIT chauffé. Un
 * futur changement de l'algorithme se compare sur ces mêmes paires : le nombre de doublons doit
 * rester identique.
 *
 * <p>Usage : {@code java -cp core/target/classes:core/target/test-classes
 * fr.strapontin.core.BancCorrespondance} (depuis {@code java/core}).
 *
 * @author Kwaite
 */
public final class BancCorrespondance {

  private static final Path SALLES = Path.of("src/test/resources/normalisation/salles-reelles.txt");
  private static final Path ARTISTES =
      Path.of("src/test/resources/normalisation/artistes-reels.txt");
  private static final int TOURS_CHAUFFAGE = 2;
  private static final int TOURS_MESURE = 5;

  private BancCorrespondance() {}

  /**
   * @param args inutilisé
   * @throws IOException si un corpus est illisible
   */
  public static void main(final String[] args) throws IOException {
    mesurer("estDoublonSalle", lire(SALLES), CorrespondanceSalle::estDoublonSalle);
    mesurer("estDoublonArtiste", lire(ARTISTES), CorrespondanceArtiste::estDoublonArtiste);
  }

  private static List<String> lire(final Path fichier) throws IOException {
    return Files.readAllLines(fichier, StandardCharsets.UTF_8).stream()
        .map(Normalisation::normaliserTexte)
        .filter(nom -> !nom.isEmpty())
        .distinct()
        .toList();
  }

  private static void mesurer(
      final String nom, final List<String> noms, final BiPredicate<String, String> doublon) {
    final long paires = (long) noms.size() * (noms.size() - 1) / 2;
    long doublons = 0;
    for (int tour = 0; tour < TOURS_CHAUFFAGE; tour++) {
      doublons = balayer(noms, doublon);
    }
    final long debut = System.nanoTime();
    for (int tour = 0; tour < TOURS_MESURE; tour++) {
      doublons = balayer(noms, doublon);
    }
    final double nanos = (double) (System.nanoTime() - debut) / ((double) TOURS_MESURE * paires);
    System.out.printf(
        "%-18s %d noms, %d paires, %d doublons : %.0f ns/paire (un million de paires : %.1f s)%n",
        nom, noms.size(), paires, doublons, nanos, nanos / 1000.0);
  }

  private static long balayer(final List<String> noms, final BiPredicate<String, String> doublon) {
    long trouves = 0;
    for (int i = 0; i < noms.size(); i++) {
      for (int j = i + 1; j < noms.size(); j++) {
        if (doublon.test(noms.get(i), noms.get(j))) {
          trouves++;
        }
      }
    }
    return trouves;
  }
}
