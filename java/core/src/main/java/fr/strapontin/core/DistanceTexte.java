/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

/**
 * Distances de similarité textuelle (Levenshtein, Jaro) utilisées par le dédoublonnage flou.
 *
 * <p>Extrait de {@link Normalisation} pour rester sous le seuil de complexité cyclomatique de
 * classe (50) — pure algorithmique de distance, sans dépendance à la normalisation elle-même.
 *
 * @since 2.9.1
 * @author Kwaite
 */
public final class DistanceTexte {

  private DistanceTexte() {}

  /**
   * Calcule la similarité Jaro entre deux chaînes.
   *
   * <p>Jaro est empiriquement meilleur que Levenshtein normalisé pour les noms propres courts : il
   * pondère les correspondances positionnelles sans over-pénaliser les transpositions. Utilisé à la
   * place de {@link #similarite} dans la réconciliation des artistes.
   *
   * @param a première chaîne
   * @param b deuxième chaîne
   * @return score Jaro entre 0.0 (aucune ressemblance) et 1.0 (identique)
   */
  public static double jaro(final String a, final String b) {
    if (a.equals(b)) {
      return 1.0;
    }
    final int m = a.length();
    final int n = b.length();
    if (m == 0 || n == 0) {
      return 0.0;
    }
    final int fenetre = Math.max(0, Math.max(m, n) / 2 - 1);
    final boolean[] matchA = new boolean[m];
    final boolean[] matchB = new boolean[n];
    final int matchs = rechercherCorrespondances(a, b, fenetre, matchA, matchB);
    if (matchs == 0) {
      return 0.0;
    }
    final int transpositions = compterTranspositions(a, b, matchA, matchB);
    return ((double) matchs / m + (double) matchs / n + (matchs - transpositions / 2.0) / matchs)
        / 3.0;
  }

  // Marque dans matchA/matchB les caractères de a et b qui se correspondent dans la fenêtre
  // Jaro, et retourne leur nombre. matchA/matchB sont des tampons de sortie mutés en place, pas
  // une liste variadique de valeurs — varargs induirait en erreur sur leur rôle réel.
  @SuppressWarnings("PMD.UseVarargs")
  private static int rechercherCorrespondances(
      final String a,
      final String b,
      final int fenetre,
      final boolean[] matchA,
      final boolean[] matchB) {
    final int m = a.length();
    final int n = b.length();
    int matchs = 0;
    for (int i = 0; i < m; i++) {
      final int debut = Math.max(0, i - fenetre);
      final int fin = Math.min(n - 1, i + fenetre);
      for (int j = debut; j <= fin; j++) {
        if (!matchB[j] && a.charAt(i) == b.charAt(j)) {
          matchA[i] = true;
          matchB[j] = true;
          matchs++;
          break;
        }
      }
    }
    return matchs;
  }

  // Compte, parmi les caractères déjà marqués correspondants, ceux dont l'ordre relatif diffère
  // entre a et b. matchA/matchB sont des tampons remplis par rechercherCorrespondances, pas une
  // liste variadique de valeurs.
  @SuppressWarnings("PMD.UseVarargs")
  private static int compterTranspositions(
      final String a, final String b, final boolean[] matchA, final boolean[] matchB) {
    int transpositions = 0;
    int k = 0;
    for (int i = 0; i < a.length(); i++) {
      if (matchA[i]) {
        while (!matchB[k]) {
          k++;
        }
        if (a.charAt(i) != b.charAt(k)) {
          transpositions++;
        }
        k++;
      }
    }
    return transpositions;
  }

  /**
   * Calcule la similarité Levenshtein normalisée entre deux chaînes.
   *
   * @param a première chaîne
   * @param b deuxième chaîne
   * @return score entre 0.0 (aucune ressemblance) et 1.0 (identique)
   */
  public static double similarite(final String a, final String b) {
    if (a.equals(b)) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    final int longueur = Math.max(a.length(), b.length());
    return 1.0 - (double) levenshtein(a, b) / longueur;
  }

  static int levenshtein(final String a, final String b) {
    final int m = a.length();
    final int n = b.length();
    final int[][] dp = new int[m + 1][n + 1];
    for (int i = 0; i <= m; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= n; j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= m; i++) {
      for (int j = 1; j <= n; j++) {
        if (a.charAt(i - 1) == b.charAt(j - 1)) {
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          dp[i][j] = 1 + Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
        }
      }
    }
    return dp[m][n];
  }
}
