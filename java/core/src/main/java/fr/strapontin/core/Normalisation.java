/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation de texte partagée par le dédoublonnage et les sources externes (TM, Eventim,
 * Digitick, Wikidata) : mise en forme d'un nom d'artiste/salle en clé comparable, extraction de
 * département, distances de similarité (Levenshtein, Jaro).
 *
 * @author Kwaite
 */
public final class Normalisation {

  private static final Pattern ACCENTS = Pattern.compile("[\\u0300-\\u036f]");
  private static final Pattern APOSTROPHES = Pattern.compile("[‘’`]");
  private static final Pattern TIRETS = Pattern.compile("[-–—]");
  private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern ESPACES_MULTIPLES = Pattern.compile("\\s+");
  private static final Pattern SEP_NORMALISER_CASSE = Pattern.compile("\\s+|-");
  private static final Pattern NON_ALPHANUM_STRICT = Pattern.compile("[^a-z0-9]");
  private static final Set<String> ARTICLES_FR =
      Set.of(
          "de", "du", "des", "d'", "le", "la", "les", "l'", "en", "au", "aux", "et", "à", "sur",
          "sous");

  /**
   * Sigles stylistiques TM : "$" utilisé comme "S" dans les noms d'artiste (ex. "Chri$tian").
   * Supprimé sans espace pour éviter un artiste_norm parasite ("chri tian" au lieu de
   * "christian").
   */
  private static final Pattern DOLLAR = Pattern.compile("\\$");

  /**
   * Esperluette entourée d'espaces → "and" (ex. "Katia & Marielle" → "Katia and Marielle").
   * Harmonise avec la version littérale pour que les deux fetches TM produisent le même norm.
   */
  private static final Pattern ESPERLUETTE = Pattern.compile(" & ");

  /**
   * Caractères non décomposables par NFD : NFD retourne le même codepoint, le pattern NON_ALPHANUM
   * les supprime entièrement. Ces substitutions les remplacent avant NFD.
   */
  private static final Map<Character, String> SUBSTITUTIONS_NFD =
      Map.of('ø', "o", 'Ø', "o", 'ł', "l", 'Ł', "l", 'đ', "d", 'Đ', "d", 'ŋ', "n", 'Ŋ', "n");

  private Normalisation() {}

  /**
   * Normalise un texte libre (artiste, ville) en clé de comparaison : minuscules, sans accents,
   * apostrophes/tirets remplacés par une espace, {@code $} et {@code &amp;} harmonisés, ponctuation retirée,
   * espaces multiples réduits.
   *
   * @param texte le texte à normaliser
   * @return le texte normalisé, éventuellement vide
   */
  public static String normaliserTexte(final String texte) {
    String s = texte.toLowerCase();
    for (final Map.Entry<Character, String> e : SUBSTITUTIONS_NFD.entrySet()) {
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

  /**
   * Normalise un nom de salle en clé alphanumérique minuscule sans accents.
   *
   * @param nom le nom de la salle
   * @return clé alphanumérique (peut être vide si le nom ne contient aucun caractère valide)
   */
  public static String normaliserSalleCle(final String nom) {
    final String nfd = Normalizer.normalize(nom.toLowerCase(), Normalizer.Form.NFD);
    return NON_ALPHANUM_STRICT.matcher(ACCENTS.matcher(nfd).replaceAll("")).replaceAll("");
  }

  /**
   * Normalise un nom de ville : {@link #normaliserTexte}, puis contracte "saint"/"sainte" en
   * "st"/"ste" pour aligner les formes abrégées et complètes d'une même commune.
   *
   * @param ville le nom de la ville
   * @return la ville normalisée, éventuellement vide
   */
  public static String normaliserVille(final String ville) {
    return normaliserTexte(ville).replaceAll("\\bsaint\\b", "st").replaceAll("\\bsainte\\b", "ste");
  }

  /**
   * Calcule la similarité Jaro entre deux chaînes.
   *
   * Jaro est empiriquement meilleur que Levenshtein normalisé pour les noms propres courts : il
   * pondère les correspondances positionnelles sans over-pénaliser les transpositions. Utilisé à
   * la place de {@link #similarite} dans la réconciliation des artistes.
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
  // Jaro, et retourne leur nombre.
  private static int rechercherCorrespondances(
      final String a, final String b, final int fenetre, final boolean[] matchA, final boolean[] matchB) {
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
  // entre a et b.
  private static int compterTranspositions(final String a, final String b, final boolean[] matchA, final boolean[] matchB) {
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

  /**
   * Extrait le code département depuis un code postal français.
   *
   * Les DOM-TOM commençant par 97 retournent les 3 premiers chiffres. La Corse (2A/2B) est déjà
   * normalisée en "20" à la saisie.
   *
   * @param codePostal code postal (peut être null, vide, ou contenir des espaces superflus)
   * @return code département à 2 ou 3 chiffres, ou null si absent/invalide
   */
  public static String extraireDeptFrancais(final String codePostal) {
    if (codePostal == null || codePostal.isBlank()) {
      return null;
    }
    final String cp = codePostal.trim();
    if (cp.startsWith("97")) {
      return cp.substring(0, 3);
    }
    final String dept = cp.substring(0, Math.min(2, cp.length()));
    return dept.isEmpty() ? null : dept;
  }

  /**
   * Convertit un nom tout-majuscule en title case français.
   *
   * Les articles courants restent en minuscules sauf en début de chaîne. Si la chaîne n'est pas
   * entièrement en majuscules, elle est retournée sans modification.
   *
   * @param nom nom à normaliser
   * @return nom en title case ou nom inchangé
   */
  public static String normaliserCasse(final String nom) {
    if (!nom.equals(nom.toUpperCase())) {
      return nom;
    }
    final Matcher m = SEP_NORMALISER_CASSE.matcher(nom);
    final StringBuilder sb = new StringBuilder();
    boolean debutPhrase = true;
    int dernier = 0;
    while (m.find()) {
      final String mot = nom.substring(dernier, m.start());
      if (!mot.isEmpty()) {
        final String bas = mot.toLowerCase();
        if (!debutPhrase && ARTICLES_FR.contains(bas)) {
          sb.append(bas);
        } else {
          sb.append(capitaliserAvecApostrophe(bas));
        }
      }
      final String sep = m.group();
      sb.append(sep);
      debutPhrase = sep.equals("-");
      dernier = m.end();
    }
    final String fin = nom.substring(dernier);
    if (!fin.isEmpty()) {
      final String bas = fin.toLowerCase();
      if (!debutPhrase && ARTICLES_FR.contains(bas)) {
        sb.append(bas);
      } else {
        sb.append(capitaliserAvecApostrophe(bas));
      }
    }
    return sb.toString();
  }

  private static String capitaliserAvecApostrophe(final String mot) {
    if (mot.isEmpty()) {
      return mot;
    }
    final StringBuilder sb = new StringBuilder();
    boolean majSuivante = true;
    for (int i = 0; i < mot.length(); i++) {
      final char c = mot.charAt(i);
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
