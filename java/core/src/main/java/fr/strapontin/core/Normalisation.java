/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation de texte partagée par le dédoublonnage et les sources externes
 * (TM, Eventim, Wikidata) : mise en forme d'un nom d'artiste/salle en
 * clé comparable, extraction de département, distances de similarité
 * (Levenshtein, Jaro).
 *
 * @author Kwaite
 */
public final class Normalisation {

  private static final Pattern ACCENTS = Pattern.compile("[\\u0300-\\u036f]");
  // Accents (marques combinantes) et « $ » : supprimés sans espace.
  private static final Pattern ACCENTS_ET_DOLLAR = Pattern.compile("[\\u0300-\\u036f$]");
  // Toute suite hors [a-z0-9] (ponctuation, tirets, espaces) devient une espace.
  private static final Pattern SEPARATEURS = Pattern.compile("[^a-z0-9]+");
  private static final Pattern SAINT = Pattern.compile("\\bsaint\\b");
  private static final Pattern SAINTE = Pattern.compile("\\bsainte\\b");
  private static final Pattern SEP_NORMALISER_CASSE = Pattern.compile("\\s+|-");
  private static final Pattern NON_ALPHANUM_STRICT = Pattern.compile("[^a-z0-9]");
  // Cinq chiffres en tête (« 93200–93380 » compris), ou un département seul.
  private static final Pattern CODE_POSTAL_FRANCAIS = Pattern.compile("^(\\d{5}(?!\\d)|\\d{2}$)");
  private static final Set<String> ARTICLES_FR =
      Set.of(
          "de", "du", "des", "d'", "le", "la", "les", "l'", "en", "au", "aux", "et", "à", "sur",
          "sous");

  /**
   * Esperluette entourée de séparateurs (espace, apostrophe, tiret) → "and" (ex.
   * "Katia & Marielle" → "Katia and Marielle"). Harmonise avec la version
   * littérale pour que les deux fetches TM produisent le même norm. Les
   * apostrophes et tirets voisins sont déjà des espaces dans la forme
   * normalisée : ils comptent ici comme telles.
   */
  private static final Pattern ESPERLUETTE = Pattern.compile("[ ‘’`\\-–—]&[ ‘’`\\-–—]");

  private Normalisation() {}

  /**
   * Normalise un texte libre (artiste, ville) en clé de comparaison :
   * minuscules, sans accents, apostrophes/tirets remplacés par une
   * espace, {@code $}/{@code &amp;} harmonisés, ponctuation retirée.
   *
   * @param texte le texte à normaliser
   * @return le texte normalisé, éventuellement vide
   */
  public static String normaliserTexte(final String texte) {
    if (TexteRapide.estDejaNormalise(texte)) {
      return texte;
    }
    String texteNorm = TexteRapide.substituerNonDecomposables(texte.toLowerCase(Locale.ROOT));
    texteNorm = Normalizer.normalize(texteNorm, Normalizer.Form.NFD);
    texteNorm = ACCENTS_ET_DOLLAR.matcher(texteNorm).replaceAll("");
    texteNorm = ESPERLUETTE.matcher(texteNorm).replaceAll(" and ");
    return SEPARATEURS.matcher(texteNorm).replaceAll(" ").trim();
  }

  /**
   * Normalise un nom de salle en clé alphanumérique minuscule sans accents.
   *
   * @param nom le nom de la salle
   * @return clé alphanumérique (peut être vide si le nom ne contient aucun
   *     caractère valide)
   */
  public static String normaliserSalleCle(final String nom) {
    final String ascii = TexteRapide.cleDepuisAscii(nom);
    if (ascii != null) {
      return ascii;
    }
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
    final String normalisee = normaliserTexte(ville);
    if (!normalisee.contains("saint")) {
      return normalisee;
    }
    return SAINTE.matcher(SAINT.matcher(normalisee).replaceAll("st")).replaceAll("ste");
  }

  /**
   * Extrait le code département depuis un code postal français
   * (DOM-TOM 97x sur 3 chiffres, Corse 2A/2B déjà en "20").
   * @param codePostal peut être null, vide, avec espaces superflus
   * @return code département à 2 ou 3 chiffres, ou null si absent ou étranger
   *     (Rockhal, « 4361 », rangée dans le 43)
   */
  public static String extraireDeptFrancais(final String codePostal) {
    if (codePostal == null || !CODE_POSTAL_FRANCAIS.matcher(codePostal.trim()).find()) {
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
   * Convertit un nom tout-majuscule en title case français,
   * articles restant en minuscules sauf en début de chaîne.
   * @param nom nom à normaliser
   * @return nom en title case, ou inchangé si pas tout-majuscule
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
