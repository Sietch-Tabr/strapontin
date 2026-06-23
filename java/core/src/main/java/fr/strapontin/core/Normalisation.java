package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

public final class Normalisation {

  private static final Pattern ACCENTS = Pattern.compile("[\\u0300-\\u036f]");
  private static final Pattern APOSTROPHES = Pattern.compile("[‘’`]");
  private static final Pattern TIRETS = Pattern.compile("[-–—]");
  private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern ESPACES_MULTIPLES = Pattern.compile("\\s+");

  /// Caractères non décomposables par NFD : NFD retourne le même codepoint, le pattern
  /// NON_ALPHANUM les supprime entièrement. Ces substitutions les remplacent avant NFD.
  private static final Map<Character, String> SUBSTITUTIONS_NFD =
      Map.of('ø', "o", 'Ø', "o", 'ł', "l", 'Ł', "l", 'đ', "d", 'Đ', "d", 'ŋ', "n", 'Ŋ', "n");

  private Normalisation() {}

  public static String normaliserTexte(String texte) {
    String s = texte.toLowerCase();
    for (Map.Entry<Character, String> e : SUBSTITUTIONS_NFD.entrySet()) {
      s = s.replace(String.valueOf(e.getKey()), e.getValue());
    }
    s = Normalizer.normalize(s, Normalizer.Form.NFD);
    s = ACCENTS.matcher(s).replaceAll("");
    s = APOSTROPHES.matcher(s).replaceAll(" ");
    s = TIRETS.matcher(s).replaceAll(" ");
    s = NON_ALPHANUM.matcher(s).replaceAll(" ");
    return ESPACES_MULTIPLES.matcher(s).replaceAll(" ").trim();
  }

  /// Normalise un nom de salle en clé alphanumérique minuscule sans accents.
  ///
  /// @param nom le nom de la salle
  /// @return clé alphanumérique (peut être vide si le nom ne contient aucun caractère valide)
  public static String normaliserSalleCle(String nom) {
    String nfd = java.text.Normalizer.normalize(nom.toLowerCase(), java.text.Normalizer.Form.NFD);
    return nfd.replaceAll("[\\u0300-\\u036f]", "").replaceAll("[^a-z0-9]", "");
  }

  public static String normaliserVille(String ville) {
    return normaliserTexte(ville).replaceAll("\\bsaint\\b", "st").replaceAll("\\bsainte\\b", "ste");
  }

  public static double similarite(String a, String b) {
    if (a.equals(b)) return 1.0;
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    int longueur = Math.max(a.length(), b.length());
    return 1.0 - (double) levenshtein(a, b) / longueur;
  }

  static int levenshtein(String a, String b) {
    int m = a.length();
    int n = b.length();
    int[][] dp = new int[m + 1][n + 1];
    for (int i = 0; i <= m; i++) dp[i][0] = i;
    for (int j = 0; j <= n; j++) dp[0][j] = j;
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
