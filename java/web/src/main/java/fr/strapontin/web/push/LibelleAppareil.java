/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import java.util.Locale;

/**
 * Nom lisible d'un appareil (« Chrome sur Android »), déduit de l'en-tête {@code User-Agent} de
 * la requête d'abonnement : de quoi reconnaître ses appareils, sans stocker l'en-tête entier.
 * @since 3.0.6
 * @author Kwaite
 */
final class LibelleAppareil {

  private LibelleAppareil() {}

  /**
   * @param userAgent en-tête {@code User-Agent}, peut être null
   * @return navigateur suivi du système (« Firefox sur Windows »), ou null si illisible
   */
  /* package-private : appelé par PushController (même package). */
  static String depuisUserAgent(final String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    final String ua = userAgent.toLowerCase(Locale.ROOT);
    final String navigateur = navigateur(ua);
    final String systeme = systeme(ua);
    if (navigateur == null) {
      return systeme;
    }
    return systeme == null ? navigateur : navigateur + " sur " + systeme;
  }

  // Ordre significatif : Edge, Opera et Chrome se disent aussi « Safari » ; Chrome se dit « Chrome
  // ».
  private static String navigateur(final String ua) {
    if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) {
      return "Edge";
    }
    if (ua.contains("opr/") || ua.contains("opera")) {
      return "Opera";
    }
    if (ua.contains("firefox/") || ua.contains("fxios/")) {
      return "Firefox";
    }
    if (ua.contains("chrome/") || ua.contains("crios/")) {
      return "Chrome";
    }
    return ua.contains("safari/") ? "Safari" : null;
  }

  private static String systeme(final String ua) {
    if (ua.contains("iphone") || ua.contains("ipad")) {
      return "iOS";
    }
    if (ua.contains("android")) {
      return "Android";
    }
    if (ua.contains("windows")) {
      return "Windows";
    }
    if (ua.contains("cros")) {
      return "ChromeOS";
    }
    if (ua.contains("macintosh") || ua.contains("mac os x")) {
      return "macOS";
    }
    return ua.contains("linux") ? "Linux" : null;
  }
}
