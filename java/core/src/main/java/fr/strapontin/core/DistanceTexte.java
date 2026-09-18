/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Distances de similarité textuelle (Levenshtein, Jaro)
 * utilisées par le dédoublonnage flou — extrait de {@link
 * Normalisation}.
 * @since 2.9.1
 * @author Kwaite
 */
public final class DistanceTexte {

  private DistanceTexte() {}

  /**
   * Similarité Jaro entre deux chaînes — meilleur que
   * Levenshtein pour les noms propres courts (détail :
   * CLAUDE.md, Dédoublonnage inter-sources).
   * @param chaineA première chaîne
   * @param chaineB deuxième chaîne
   * @return score entre 0.0 (aucune ressemblance) et 1.0 (identique)
   */
  public static double jaro(final String chaineA, final String chaineB) {
    if (chaineA.equals(chaineB)) {
      return 1.0;
    }
    final int longueurA = chaineA.length();
    final int longueurB = chaineB.length();
    if (longueurA == 0 || longueurB == 0) {
      return 0.0;
    }
    final int fenetre = Math.max(0, Math.max(longueurA, longueurB) / 2 - 1);
    final boolean[] matchA = new boolean[longueurA];
    final boolean[] matchB = new boolean[longueurB];
    final int matchs = rechercherCorrespondances(chaineA, chaineB, fenetre, matchA, matchB);
    if (matchs == 0) {
      return 0.0;
    }
    final int transpositions = compterTranspositions(chaineA, chaineB, matchA, matchB);
    return ((double) matchs / longueurA
            + (double) matchs / longueurB
            + (matchs - transpositions / 2.0) / matchs)
        / 3.0;
  }

  // Marque dans matchA/matchB les caractères de chaineA et chaineB qui se
  // correspondent dans la fenêtre Jaro, et retourne leur nombre. matchA/matchB
  // sont des tampons de sortie mutés en place, pas une liste variadique de
  // valeurs — varargs induirait en erreur sur leur rôle réel.
  @SuppressWarnings("PMD.UseVarargs")
  private static int rechercherCorrespondances(
      final String chaineA,
      final String chaineB,
      final int fenetre,
      final boolean[] matchA,
      final boolean[] matchB) {
    final int longueurA = chaineA.length();
    final int longueurB = chaineB.length();
    int matchs = 0;
    for (int i = 0; i < longueurA; i++) {
      final int debut = Math.max(0, i - fenetre);
      final int fin = Math.min(longueurB - 1, i + fenetre);
      for (int j = debut; j <= fin; j++) {
        if (!matchB[j] && chaineA.charAt(i) == chaineB.charAt(j)) {
          matchA[i] = true;
          matchB[j] = true;
          matchs++;
          break;
        }
      }
    }
    return matchs;
  }

  // Compte, parmi les caractères déjà marqués correspondants, ceux dont l'ordre
  // relatif diffère entre chaineA et chaineB. matchA/matchB sont des tampons
  // remplis par rechercherCorrespondances, pas une liste variadique de valeurs.
  @SuppressWarnings("PMD.UseVarargs")
  private static int compterTranspositions(
      final String chaineA, final String chaineB, final boolean[] matchA, final boolean[] matchB) {
    int transpositions = 0;
    int indexB = 0;
    for (int i = 0; i < chaineA.length(); i++) {
      if (matchA[i]) {
        while (!matchB[indexB]) {
          indexB++;
        }
        if (chaineA.charAt(i) != chaineB.charAt(indexB)) {
          transpositions++;
        }
        indexB++;
      }
    }
    return transpositions;
  }

  /**
   * Calcule la similarité Levenshtein normalisée entre deux chaînes.
   *
   * @param chaineA première chaîne
   * @param chaineB deuxième chaîne
   * @return score entre 0.0 (aucune ressemblance) et 1.0 (identique)
   */
  public static double similarite(final String chaineA, final String chaineB) {
    if (chaineA.equals(chaineB)) {
      return 1.0;
    }
    if (chaineA.isEmpty() || chaineB.isEmpty()) {
      return 0.0;
    }
    final int longueur = Math.max(chaineA.length(), chaineB.length());
    return 1.0 - (double) levenshtein(chaineA, chaineB) / longueur;
  }

  private static int levenshtein(final String chaineA, final String chaineB) {
    final int longueurA = chaineA.length();
    final int longueurB = chaineB.length();
    final int[][] dp = new int[longueurA + 1][longueurB + 1];
    for (int i = 0; i <= longueurA; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= longueurB; j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= longueurA; i++) {
      for (int j = 1; j <= longueurB; j++) {
        if (chaineA.charAt(i - 1) == chaineB.charAt(j - 1)) {
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          dp[i][j] = 1 + Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
        }
      }
    }
    return dp[longueurA][longueurB];
  }
}
