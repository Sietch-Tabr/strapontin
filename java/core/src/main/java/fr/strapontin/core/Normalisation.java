package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Normalisation {

  private static final Pattern ACCENTS = Pattern.compile("[\\u0300-\\u036f]");
  private static final Pattern APOSTROPHES = Pattern.compile("[‘’`]");
  private static final Pattern TIRETS = Pattern.compile("[-–—]");
  private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern ESPACES_MULTIPLES = Pattern.compile("\\s+");
  private static final Pattern SEP_NORMALISER_CASSE = Pattern.compile("\\s+|-");
  private static final Set<String> ARTICLES_FR =
      Set.of(
          "de", "du", "des", "d'", "le", "la", "les", "l'", "en", "au", "aux", "et", "à", "sur",
          "sous");
  /// Sigles stylistiques TM : "$" utilisé comme "S" dans les noms d'artiste (ex. "Chri$tian").
  /// Supprimé sans espace pour éviter un artiste_norm parasite ("chri tian" au lieu de
  // "christian").
  private static final Pattern DOLLAR = Pattern.compile("\\$");
  /// Esperluette entourée d'espaces → "and" (ex. "Katia & Marielle" → "Katia and Marielle").
  /// Harmonise avec la version littérale pour que les deux fetches TM produisent le même norm.
  private static final Pattern ESPERLUETTE = Pattern.compile(" & ");

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
    s = DOLLAR.matcher(s).replaceAll("");
    s = ESPERLUETTE.matcher(s).replaceAll(" and ");
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

  /// Calcule la similarité Jaro entre deux chaînes.
  ///
  /// Jaro est empiriquement meilleur que Levenshtein normalisé pour les noms propres courts :
  /// il pondère les correspondances positionnelles sans over-pénaliser les transpositions.
  /// Utilisé à la place de {@link #similarite} dans la réconciliation des artistes.
  ///
  /// @param a première chaîne
  /// @param b deuxième chaîne
  /// @return score Jaro entre 0.0 (aucune ressemblance) et 1.0 (identique)
  public static double jaro(String a, String b) {
    if (a.equals(b)) return 1.0;
    int m = a.length();
    int n = b.length();
    if (m == 0 || n == 0) return 0.0;
    int fenetre = Math.max(m, n) / 2 - 1;
    if (fenetre < 0) fenetre = 0;
    boolean[] matchA = new boolean[m];
    boolean[] matchB = new boolean[n];
    int matchs = 0;
    for (int i = 0; i < m; i++) {
      int debut = Math.max(0, i - fenetre);
      int fin = Math.min(n - 1, i + fenetre);
      for (int j = debut; j <= fin; j++) {
        if (!matchB[j] && a.charAt(i) == b.charAt(j)) {
          matchA[i] = true;
          matchB[j] = true;
          matchs++;
          break;
        }
      }
    }
    if (matchs == 0) return 0.0;
    int transpositions = 0;
    int k = 0;
    for (int i = 0; i < m; i++) {
      if (matchA[i]) {
        while (!matchB[k]) k++;
        if (a.charAt(i) != b.charAt(k)) transpositions++;
        k++;
      }
    }
    return ((double) matchs / m + (double) matchs / n + (matchs - transpositions / 2.0) / matchs)
        / 3.0;
  }

  public static double similarite(String a, String b) {
    if (a.equals(b)) return 1.0;
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    int longueur = Math.max(a.length(), b.length());
    return 1.0 - (double) levenshtein(a, b) / longueur;
  }

  /// Extrait le code département depuis un code postal français.
  ///
  /// Les DOM-TOM commençant par 97 retournent les 3 premiers chiffres. La Corse (2A/2B) est
  /// déjà normalisée en "20" à la saisie.
  ///
  /// @param codePostal code postal (peut être null, vide, ou contenir des espaces superflus)
  /// @return code département à 2 ou 3 chiffres, ou null si absent/invalide
  public static String extraireDeptFrancais(String codePostal) {
    if (codePostal == null || codePostal.isBlank()) return null;
    String cp = codePostal.trim();
    if (cp.startsWith("97")) return cp.substring(0, 3);
    String dept = cp.substring(0, Math.min(2, cp.length()));
    return dept.isEmpty() ? null : dept;
  }

  /// Convertit un nom tout-majuscule en title case français.
  ///
  /// Les articles courants restent en minuscules sauf en début de chaîne. Si la chaîne
  /// n'est pas entièrement en majuscules, elle est retournée sans modification.
  ///
  /// @param nom nom à normaliser
  /// @return nom en title case ou nom inchangé
  public static String normaliserCasse(String nom) {
    if (!nom.equals(nom.toUpperCase())) return nom;
    Matcher m = SEP_NORMALISER_CASSE.matcher(nom);
    StringBuilder sb = new StringBuilder();
    boolean debutPhrase = true;
    int dernier = 0;
    while (m.find()) {
      String mot = nom.substring(dernier, m.start());
      if (!mot.isEmpty()) {
        String bas = mot.toLowerCase();
        if (!debutPhrase && ARTICLES_FR.contains(bas)) {
          sb.append(bas);
        } else {
          sb.append(capitaliserAvecApostrophe(bas));
        }
      }
      String sep = m.group();
      sb.append(sep);
      debutPhrase = sep.equals("-");
      dernier = m.end();
    }
    String fin = nom.substring(dernier);
    if (!fin.isEmpty()) {
      String bas = fin.toLowerCase();
      if (!debutPhrase && ARTICLES_FR.contains(bas)) {
        sb.append(bas);
      } else {
        sb.append(capitaliserAvecApostrophe(bas));
      }
    }
    return sb.toString();
  }

  private static String capitaliserAvecApostrophe(String mot) {
    if (mot.isEmpty()) return mot;
    StringBuilder sb = new StringBuilder();
    boolean majSuivante = true;
    for (int i = 0; i < mot.length(); i++) {
      char c = mot.charAt(i);
      if (c == '\'' || c == '’') {
        sb.append(c);
        majSuivante = true;
      } else if (majSuivante) {
        sb.append(Character.toUpperCase(c));
        majSuivante = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
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
