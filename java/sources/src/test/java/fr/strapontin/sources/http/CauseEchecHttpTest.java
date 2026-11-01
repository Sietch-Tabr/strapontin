/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Tests unitaires de {@link CauseEchecHttp}.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class CauseEchecHttpTest {

  @Test
  void depuis_connexionRefuseeSousUneErreurDAcces_retourneConnexionRefusee() {
    final var echec = new ResourceAccessException("I/O error", new ConnectException("refused"));

    assertEquals("connexion refusée", CauseEchecHttp.depuis(echec));
  }

  @Test
  void depuis_echangeTlsInterrompu_retourneEchangeTls() {
    final var echec =
        new ResourceAccessException(
            "I/O error", new SSLHandshakeException("Remote host terminated the handshake"));

    assertEquals("échange TLS interrompu", CauseEchecHttp.depuis(echec));
  }

  @Test
  void depuis_finDeFluxPrematuree_retourneConnexionFermee() {
    final var echec =
        new ResourceAccessException("I/O error", new IOException("EOF reached while reading"));

    assertEquals("connexion fermée par le serveur", CauseEchecHttp.depuis(echec));
  }

  @Test
  void depuis_delaiEtDns_retourneLesCausesDediees() {
    assertEquals(
        "délai dépassé",
        CauseEchecHttp.depuis(new ResourceAccessException("x", new HttpTimeoutException("t"))));
    assertEquals(
        "résolution DNS impossible",
        CauseEchecHttp.depuis(new ResourceAccessException("x", new UnknownHostException("h"))));
  }

  @Test
  void depuis_statutsHttp_distinguentLimitationRefusEtErreurDuService() {
    assertEquals(
        "HTTP 429 (limitation de débit)",
        CauseEchecHttp.depuis(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "x", null, new byte[0], null)));
    assertEquals(
        "HTTP 403 (accès refusé)",
        CauseEchecHttp.depuis(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "x", null, new byte[0], null)));
    assertEquals(
        "HTTP 503 (erreur du service)",
        CauseEchecHttp.depuis(
            HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "x", null, new byte[0], null)));
  }

  @Test
  void depuis_erreurInconnue_retourneAutreErreur() {
    assertEquals(CauseEchecHttp.AUTRE, CauseEchecHttp.depuis(new IllegalStateException("x")));
  }
}
