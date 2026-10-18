/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import java.util.List;
import java.util.Locale;

/**
 * Nom lisible d'un appareil (« Chrome sur Android »), déduit de l'en-tête
 * {@code User-Agent} de la requête d'abonnement : de quoi reconnaître ses
 * appareils, sans stocker l'en-tête entier.
 * @since 3.0.6
 * @author Kwaite
 */
final class LibelleAppareil {

  // Ordre significatif : Edge, Opera et Chrome se disent aussi « Safari » ;
  // Chrome se dit « Chrome ».
  private static final List<Reconnaissance> NAVIGATEURS =
      List.of(
          new Reconnaissance("Edge", List.of("edg/", "edga/", "edgios/")),
          new Reconnaissance("Opera", List.of("opr/", "opera")),
          new Reconnaissance("Firefox", List.of("firefox/", "fxios/")),
          new Reconnaissance("Chrome", List.of("chrome/", "crios/")),
          new Reconnaissance("Safari", List.of("safari/")));

  private static final List<Reconnaissance> SYSTEMES =
      List.of(
          new Reconnaissance("iOS", List.of("iphone", "ipad")),
          new Reconnaissance("Android", List.of("android")),
          new Reconnaissance("Windows", List.of("windows")),
          new Reconnaissance("ChromeOS", List.of("cros")),
          new Reconnaissance("macOS", List.of("macintosh", "mac os x")),
          new Reconnaissance("Linux", List.of("linux")));

  private LibelleAppareil() {}

  /**
   * @param userAgent en-tête {@code User-Agent}, peut être null
   * @return navigateur suivi du système (« Firefox sur Windows »), ou null si
   *     illisible
   */
  /* package-private : appelé par PushController (même package). */
  static String depuisUserAgent(final String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    final String ua = userAgent.toLowerCase(Locale.ROOT);
    final String navigateur = reconnaitre(NAVIGATEURS, ua);
    final String systeme = reconnaitre(SYSTEMES, ua);
    if (navigateur == null) {
      return systeme;
    }
    return systeme == null ? navigateur : navigateur + " sur " + systeme;
  }

  /** Libellé affiché et marqueurs, en minuscules, qui le font reconnaître. */
  private record Reconnaissance(String libelle, List<String> marqueurs) {}

  private static String reconnaitre(final List<Reconnaissance> candidats, final String ua) {
    return candidats.stream()
        .filter(c -> c.marqueurs().stream().anyMatch(ua::contains))
        .map(Reconnaissance::libelle)
        .findFirst()
        .orElse(null);
  }
}
