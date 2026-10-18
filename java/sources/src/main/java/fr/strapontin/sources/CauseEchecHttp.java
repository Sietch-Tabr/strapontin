/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import javax.net.ssl.SSLException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cause lisible d'un appel HTTP en échec (réseau, refus du service,
 * limitation de débit), pour attribuer les échecs d'une source plutôt
 * que de les compter en bloc.
 * @since 3.0.6
 * @author Kwaite
 */
final class CauseEchecHttp {

  /** Cause par défaut d'une erreur non reconnue. */
  /* package-private : valeur de repli des sources du même package. */
  static final String AUTRE = "autre erreur";

  private static final int STATUT_LIMITATION = 429;
  private static final int STATUT_ACCES_REFUSE = 403;
  private static final int STATUT_ERREUR_SERVICE = 500;

  private CauseEchecHttp() {}

  /**
   * @param echec exception levée par l'appel, ou sa cause racine
   * @return cause lisible, la plus précise de la chaîne des causes
   */
  /* package-private : appelé par les sources HTTP du même package. */
  static String depuis(final Throwable echec) {
    for (Throwable maillon = echec; maillon != null; maillon = maillon.getCause()) {
      final String cause = causeDe(maillon);
      if (cause != null) {
        return cause;
      }
    }
    return AUTRE;
  }

  private static String causeDe(final Throwable maillon) {
    if (maillon instanceof RestClientResponseException reponse) {
      return causeStatut(reponse.getStatusCode().value());
    }
    if (maillon instanceof ConnectException) {
      return "connexion refusée";
    }
    if (maillon instanceof SocketTimeoutException || maillon instanceof HttpTimeoutException) {
      return "délai dépassé";
    }
    if (maillon instanceof UnknownHostException) {
      return "résolution DNS impossible";
    }
    if (maillon instanceof SSLException) {
      return "échange TLS interrompu";
    }
    final String message =
        maillon.getMessage() == null ? "" : maillon.getMessage().toUpperCase(Locale.ROOT);
    return message.contains("EOF") ? "connexion fermée par le serveur" : null;
  }

  private static String causeStatut(final int statut) {
    if (statut == STATUT_LIMITATION) {
      return "HTTP 429 (limitation de débit)";
    }
    if (statut == STATUT_ACCES_REFUSE) {
      return "HTTP 403 (accès refusé)";
    }
    return statut >= STATUT_ERREUR_SERVICE
        ? "HTTP " + statut + " (erreur du service)"
        : "HTTP " + statut;
  }
}
