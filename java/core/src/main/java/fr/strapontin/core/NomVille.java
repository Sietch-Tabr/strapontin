/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Forme d'affichage d'un nom de commune publié par une source : sans
 * pays ni « Cedex », casse homogène (« Tremblay En France » et
 * « Tremblay en France » s'écrivent pareil).
 * @since 3.0.6
 * @author Kwaite
 */
public final class NomVille {

  private static final Pattern PAYS = Pattern.compile("(?i)\\s*,\\s*france$");
  private static final Pattern CEDEX = Pattern.compile("(?i)\\s+cedex(?:\\s+\\d+)?$");
  private static final Pattern ESPACES = Pattern.compile("\\s+");
  private static final Pattern SEPARATEURS = Pattern.compile("(?<=[\\s'’-])|(?=[\\s'’-])");
  private static final Set<String> PARTICULES =
      Set.of(
          "de", "du", "des", "la", "le", "les", "en", "sur", "sous", "aux", "au", "et", "l", "d");

  private NomVille() {}

  /**
   * @param ville nom de commune tel que publié par la source, ou {@code null}
   * @return le nom nettoyé, ou {@code null} si la source n'en donne pas
   */
  public static String nettoyer(final String ville) {
    if (ville == null) {
      return null;
    }
    String propre = PAYS.matcher(ville.strip()).replaceFirst("");
    propre = CEDEX.matcher(propre).replaceFirst("");
    propre = ESPACES.matcher(propre).replaceAll(" ").strip();
    return propre.isEmpty() ? null : mettreEnForme(propre);
  }

  private static String mettreEnForme(final String nom) {
    final StringBuilder resultat = new StringBuilder(nom.length());
    for (final String morceau : SEPARATEURS.split(nom)) {
      final String minuscules = morceau.toLowerCase(Locale.ROOT);
      final boolean debut = resultat.isEmpty();
      if (!debut && PARTICULES.contains(minuscules)) {
        resultat.append(minuscules);
      } else {
        resultat.append(majusculeInitiale(minuscules));
      }
    }
    return resultat.toString();
  }

  private static String majusculeInitiale(final String morceau) {
    if (morceau.isEmpty()) {
      return morceau;
    }
    final int premier = morceau.codePointAt(0);
    return new String(Character.toChars(Character.toUpperCase(premier)))
        + morceau.substring(Character.charCount(premier));
  }
}
