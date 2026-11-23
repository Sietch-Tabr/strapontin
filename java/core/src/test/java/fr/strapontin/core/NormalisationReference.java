/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implémentation de référence de la normalisation, écrite pour la lisibilité (une passe par
 * règle) : la sortie de {@link Normalisation} doit lui rester identique sur tout le corpus de noms
 * réels et sur les cas limites. Sert aussi de point de comparaison au banc de mesure.
 *
 * @author Kwaite
 */
final class NormalisationReference {

  private static final Pattern ACCENTS = Pattern.compile("[\\u0300-\\u036f]");
  private static final Pattern APOSTROPHES = Pattern.compile("[‘’`]");
  private static final Pattern TIRETS = Pattern.compile("[-–—]");
  private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern ESPACES_MULTIPLES = Pattern.compile("\\s+");
  private static final Pattern NON_ALPHANUM_STRICT = Pattern.compile("[^a-z0-9]");
  private static final Pattern DOLLAR = Pattern.compile("\\$");
  private static final Pattern ESPERLUETTE = Pattern.compile(" & ");
  private static final Map<Character, String> SUBSTITUTIONS_NFD =
      Map.of('ø', "o", 'Ø', "o", 'ł', "l", 'Ł', "l", 'đ', "d", 'Đ', "d", 'ŋ', "n", 'Ŋ', "n");

  private NormalisationReference() {}

  static String normaliserTexte(final String texte) {
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

  static String normaliserSalleCle(final String nom) {
    final String nfd = Normalizer.normalize(nom.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    return NON_ALPHANUM_STRICT.matcher(ACCENTS.matcher(nfd).replaceAll("")).replaceAll("");
  }

  static String normaliserVille(final String ville) {
    return normaliserTexte(ville).replaceAll("\\bsaint\\b", "st").replaceAll("\\bsainte\\b", "ste");
  }
}
