/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation de texte partagée par le dédoublonnage et les sources externes
 * (TM, Eventim, Digitick, Wikidata) : mise en forme d'un nom d'artiste/salle en
 * clé comparable, extraction de département, distances de similarité
 * (Levenshtein, Jaro).
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
   * Sigles stylistiques TM : "$" utilisé comme "S" dans les noms d'artiste (ex.
   * "Chri$tian"). Supprimé sans espace pour éviter un artiste_norm parasite
   * ("chri tian" au lieu de "christian").
   */
  private static final Pattern DOLLAR = Pattern.compile("\\$");

  /**
   * Esperluette entourée d'espaces → "and" (ex. "Katia & Marielle" → "Katia and
   * Marielle"). Harmonise avec la version littérale pour que les deux fetches
   * TM produisent le même norm.
   */
  private static final Pattern ESPERLUETTE = Pattern.compile(" & ");

  /**
   * Caractères non décomposables par NFD : NFD retourne le même codepoint, le
   * pattern NON_ALPHANUM les supprime entièrement. Ces substitutions les
   * remplacent avant NFD.
   */
  private static final Map<Character, String> SUBSTITUTIONS_NFD =
      Map.of('ø', "o", 'Ø', "o", 'ł', "l", 'Ł', "l", 'đ', "d", 'Đ', "d", 'ŋ', "n", 'Ŋ', "n");

  private Normalisation() {}

  /**
   * Normalise un texte libre (artiste, ville) en clé de comparaison : minuscules, sans accents,
   * apostrophes/tirets remplacés par une espace, {@code $} et {@code &amp;} harmonisés, ponctuation
   * retirée, espaces multiples réduits.
   *
   * @param texte le texte à normaliser
   * @return le texte normalisé, éventuellement vide
   */
  public static String normaliserTexte(final String texte) {
    String texteNorm = texte.toLowerCase(Locale.ROOT);
    for (final Map.Entry<Character, String> e : SUBSTITUTIONS_NFD.entrySet()) {
      texteNorm = texteNorm.replace(String.valueOf(e.getKey()), e.getValue());
    }
    texteNorm = Normalizer.normalize(texteNorm, Normalizer.Form.NFD);
    texteNorm = ACCENTS.matcher(texteNorm).replaceAll("");
    texteNorm = APOSTROPHES.matcher(texteNorm).replaceAll(" ");
    texteNorm = TIRETS.matcher(texteNorm).replaceAll(" ");
    texteNorm = DOLLAR.matcher(texteNorm).replaceAll("");
    texteNorm = ESPERLUETTE.matcher(texteNorm).replaceAll(" and ");
    texteNorm = NON_ALPHANUM.matcher(texteNorm).replaceAll(" ");
    return ESPACES_MULTIPLES.matcher(texteNorm).replaceAll(" ").trim();
  }

  /**
   * Normalise un nom de salle en clé alphanumérique minuscule sans accents.
   *
   * @param nom le nom de la salle
   * @return clé alphanumérique (peut être vide si le nom ne contient aucun
   *     caractère valide)
   */
  public static String normaliserSalleCle(final String nom) {
    final String nfd = Normalizer.normalize(nom.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    return NON_ALPHANUM_STRICT.matcher(ACCENTS.matcher(nfd).replaceAll("")).replaceAll("");
  }

  /**
   * Normalise un nom de ville : {@link #normaliserTexte} , puis contracte
   * "saint"/"sainte" en "st"/"ste" pour aligner les formes abrégées et
   * complètes d'une même commune.
   *
   * @param ville le nom de la ville
   * @return la ville normalisée, éventuellement vide
   */
  public static String normaliserVille(final String ville) {
    return normaliserTexte(ville).replaceAll("\\bsaint\\b", "st").replaceAll("\\bsainte\\b", "ste");
  }

  /**
   * Extrait le code département depuis un code postal français.
   *
   * <p>Les DOM-TOM commençant par 97 retournent les 3 premiers chiffres. La Corse (2A/2B) est déjà
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
   * <p>Les articles courants restent en minuscules sauf en début de chaîne. Si la chaîne n'est pas
   * entièrement en majuscules, elle est retournée sans modification.
   *
   * @param nom nom à normaliser
   * @return nom en title case ou nom inchangé
   */
  // equalsIgnoreCase ne convient pas ici : le test porte sur nom lui-même (déjà
  // entièrement majuscule ou non), pas sur une comparaison insensible à la
  // casse entre deux chaînes distinctes.
  public static String normaliserCasse(final String nom) {
    if (!nom.equals(nom.toUpperCase(Locale.ROOT))) {
      return nom;
    }
    final Matcher matcher = SEP_NORMALISER_CASSE.matcher(nom);
    final StringBuilder sb = new StringBuilder();
    boolean debutPhrase = true;
    int dernier = 0;
    while (matcher.find()) {
      final String mot = nom.substring(dernier, matcher.start());
      if (!mot.isEmpty()) {
        final String bas = mot.toLowerCase(Locale.ROOT);
        if (!debutPhrase && ARTICLES_FR.contains(bas)) {
          sb.append(bas);
        } else {
          sb.append(capitaliserAvecApostrophe(bas));
        }
      }
      final String sep = matcher.group();
      sb.append(sep);
      debutPhrase = "-".equals(sep);
      dernier = matcher.end();
    }
    final String fin = nom.substring(dernier);
    if (!fin.isEmpty()) {
      final String bas = fin.toLowerCase(Locale.ROOT);
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
      final char caractere = mot.charAt(i);
      if (caractere == '\'' || caractere == '’') {
        sb.append(caractere);
        majSuivante = true;
      } else if (majSuivante) {
        sb.append(Character.toUpperCase(caractere));
        majSuivante = false;
      } else {
        sb.append(caractere);
      }
    }
    return sb.toString();
  }
}
