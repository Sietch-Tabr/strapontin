/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * URL d'abonnement push acceptée : services de push des navigateurs, HTTPS,
 * port standard. Liste de domaines plutôt que filtre d'IP : aucune adresse
 * interne n'est atteignable, même par un nom qui y résoudrait ensuite.
 * @since 3.0.2
 * @author Kwaite
 */
public final class EndpointPush {

  // FCM (Chromium), autopush (Firefox), Apple (Safari), WNS (ancien Edge).
  private static final List<String> DOMAINES_SERVICES_PUSH =
      List.of(
          "fcm.googleapis.com",
          "android.googleapis.com",
          "push.services.mozilla.com",
          "push.apple.com",
          "notify.windows.com");
  private static final int PORT_HTTPS = 443;

  private EndpointPush() {}

  /**
   * @param endpoint URL de l'abonnement, peut être null
   * @return true si HTTPS, sans identifiants, port standard, et hôte égal à
   *     un domaine de service de push ou à l'un de ses sous-domaines
   */
  public static boolean estAutorise(final String endpoint) {
    if (endpoint == null) {
      return false;
    }
    final URI uri;
    try {
      uri = new URI(endpoint);
    } catch (URISyntaxException e) {
      return false;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getHost() == null
        || (uri.getPort() != -1 && uri.getPort() != PORT_HTTPS)) {
      return false;
    }
    final String hote = uri.getHost().toLowerCase(Locale.ROOT);
    return DOMAINES_SERVICES_PUSH.stream()
        .anyMatch(domaine -> hote.equals(domaine) || hote.endsWith("." + domaine));
  }
}
