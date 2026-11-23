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
import java.util.function.Function;

/**
 * Banc de mesure rejouable de la normalisation sur des noms réels (artistes, salles, villes) : coût
 * moyen d'un appel de l'implémentation de référence et de {@link Normalisation}, JIT chauffé.
 *
 * <p>Usage : {@code java -cp core/target/classes:core/target/test-classes
 * fr.strapontin.core.BancNormalisation [fichier de noms, un par ligne]} ; sans argument, le corpus
 * de {@code src/test/resources/normalisation/noms-reels.txt}.
 *
 * @author Kwaite
 */
public final class BancNormalisation {

  private static final int TOURS_CHAUFFAGE = 30;
  private static final int TOURS_MESURE = 60;
  private static long puits;

  private BancNormalisation() {}

  /**
   * @param args fichier de noms optionnel
   * @throws IOException si le corpus est illisible
   */
  public static void main(final String[] args) throws IOException {
    final Path corpus =
        Path.of(args.length > 0 ? args[0] : "src/test/resources/normalisation/noms-reels.txt");
    final List<String> noms = Files.readAllLines(corpus, StandardCharsets.UTF_8);
    System.out.printf("Corpus : %d noms (%s)%n", noms.size(), corpus);
    mesurer(
        "normaliserTexte",
        noms,
        NormalisationReference::normaliserTexte,
        Normalisation::normaliserTexte);
    mesurer(
        "normaliserVille",
        noms,
        NormalisationReference::normaliserVille,
        Normalisation::normaliserVille);
    mesurer(
        "normaliserSalleCle",
        noms,
        NormalisationReference::normaliserSalleCle,
        Normalisation::normaliserSalleCle);
    System.out.println("(puits " + puits + ")");
  }

  private static void mesurer(
      final String nom,
      final List<String> noms,
      final Function<String, String> reference,
      final Function<String, String> courante) {
    final double ref = nanosParAppel(noms, reference);
    final double cou = nanosParAppel(noms, courante);
    System.out.printf(
        "%-20s référence %7.0f ns/appel | courante %7.0f ns/appel | rapport %.2f%n",
        nom, ref, cou, cou / ref);
  }

  private static double nanosParAppel(final List<String> noms, final Function<String, String> f) {
    for (int tour = 0; tour < TOURS_CHAUFFAGE; tour++) {
      balayer(noms, f);
    }
    final long debut = System.nanoTime();
    for (int tour = 0; tour < TOURS_MESURE; tour++) {
      balayer(noms, f);
    }
    return (double) (System.nanoTime() - debut) / ((double) TOURS_MESURE * noms.size());
  }

  private static void balayer(final List<String> noms, final Function<String, String> f) {
    for (final String nom : noms) {
      puits += f.apply(nom).length();
    }
  }
}
