/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reconnaît d'après son titre un produit de billetterie qui vend une visite ou
 * un accès prioritaire sans spectacle. Un billet combiné (« Spectacle + Visite »)
 * reste un spectacle : son titre annonce lui-même la visite.
 * @since 3.1.3
 * @author Kwaite
 */
public final class AccesSansSpectacle {

  private static final Pattern ACCES =
      Pattern.compile("^visites?\\b|\\bpriority (pass|access)\\b|\\bfast (pass|track)\\b");

  private AccesSansSpectacle() {}

  /**
   * @param titre titre du produit tel que publié par la billetterie, peut être null
   * @return {@code true} si le titre désigne une visite ou un accès prioritaire
   *     seul, jamais pour un billet combiné (titre contenant « + »)
   */
  public static boolean estAccesSansSpectacle(final String titre) {
    if (titre == null || titre.contains("+")) {
      return false;
    }
    final String sansAccents =
        Normalizer.normalize(titre, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return ACCES
        .matcher(sansAccents.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
        .find();
  }
}
