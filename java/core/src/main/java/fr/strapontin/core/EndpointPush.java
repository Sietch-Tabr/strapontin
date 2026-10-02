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
 * Validation de l'URL d'un abonnement push, fournie par le navigateur de l'utilisateur et appelée
 * ensuite par le serveur : seuls les services de push des navigateurs sont acceptés, en HTTPS sur
 * le port standard. Une liste de domaines plutôt qu'un filtre d'adresses IP : aucune adresse
 * interne n'est atteignable, y compris par un nom de domaine qui y résoudrait après validation.
 *
 * @since 3.0.2
 * @author Kwaite
 */
public final class EndpointPush {

  // Chrome, Edge (Chromium), Opera, Brave : FCM ; Firefox : Mozilla autopush ; Safari : Apple ;
  // Edge historique : WNS.
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
   * Indique si l'URL désigne un service de push de navigateur.
   *
   * @param endpoint URL de l'abonnement, peut être null
   * @return true si l'URL est en HTTPS, sans identifiants, sur le port standard, et que son hôte
   *     est l'un des domaines de services de push ou l'un de leurs sous-domaines
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
