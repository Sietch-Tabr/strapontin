/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link WorkerProxyService} — focus sur {@code relancerJob} (v2.6.0,
 * bouton « Relancer » par job de maintenance).
 *
 * @since 2.6.0
 * @author Kwaite
 */
class WorkerProxyServiceTest {

  @AfterEach
  void nettoyerMdc() {
    MDC.clear();
  }

  // le correlationId posé en MDC par CorrelationIdFilter côté requête entrante doit être
  // propagé au worker, pour relier une action admin aux logs du job réellement exécuté.
  @Test
  void relancerFetches_correlationIdPresentDansMdc_estTransmisEnHeader() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    MDC.put("correlationId", "corr-test-123");

    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), mock(LogAdminService.class));
    service.relancerFetches();

    org.mockito.Mockito.verify(restClient.post().uri(anyString()))
        .header("X-Correlation-ID", "corr-test-123");
  }

  @Test
  void relancerJob_workerInaccessible_retourneEchecAvecStatut503() {
    final RestClient restClientEnPanne = mock(RestClient.class);
    when(restClientEnPanne.post()).thenThrow(new RuntimeException("connexion refusée"));
    final LogAdminService logAdminService = mock(LogAdminService.class);
    final WorkerProxyService service =
        new WorkerProxyService(restClientEnPanne, new ObjectMapper(), logAdminService);

    final ResultatRelance resultat = service.relancerJob("purger:salles");

    assertFalse(resultat.succes());
    assertNotNull(resultat.messageErreur());
    // une exception réseau générique (pas de réponse HTTP du tout) reste un vrai 503 —
    // distinct des cas où le worker répond mais avec un statut d'erreur (400/5xx).
    assertEquals(503, resultat.statutHttp());
  }

  // distingue le job inconnu (400 renvoyé par le worker → erreur cliente) d'une vraie
  // panne interne worker (autre code d'erreur → 502) — auparavant les deux étaient noyés dans un
  // message unique masquant la cause réelle.
  @Test
  void relancerJob_workerRepond400_retourneJobInconnuAvecStatut400() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    final HttpClientErrorException exception =
        HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
    when(restClient.post().uri(anyString(), any(Object[].class)).retrieve().toBodilessEntity())
        .thenThrow(exception);
    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), mock(LogAdminService.class));

    final ResultatRelance resultat = service.relancerJob("job-inconnu");

    assertFalse(resultat.succes());
    assertTrue(resultat.messageErreur().contains("Job inconnu"));
    assertEquals(400, resultat.statutHttp());
  }

  @Test
  void relancerJob_workerRepond500_retourneErreurInterneAvecStatut502() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    final HttpServerErrorException exception =
        HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            HttpHeaders.EMPTY,
            new byte[0],
            null);
    when(restClient.post().uri(anyString(), any(Object[].class)).retrieve().toBodilessEntity())
        .thenThrow(exception);
    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), mock(LogAdminService.class));

    final ResultatRelance resultat = service.relancerJob("purger:salles");

    assertFalse(resultat.succes());
    assertTrue(resultat.messageErreur().contains("Erreur interne worker"));
    assertEquals(502, resultat.statutHttp());
  }

  @Test
  void relancerFetches_workerRepond500_retourneErreurInterneAvecStatut502() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    final HttpServerErrorException exception =
        HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            HttpHeaders.EMPTY,
            new byte[0],
            null);
    when(restClient.post().uri(anyString()).retrieve().toBodilessEntity()).thenThrow(exception);
    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), mock(LogAdminService.class));

    final ResultatRelance resultat = service.relancerFetches();

    assertFalse(resultat.succes());
    assertEquals(502, resultat.statutHttp());
  }

  @Test
  void notifierNouvelleVersion_succes_retourneResultatPositif() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), mock(LogAdminService.class));

    final ResultatRelance resultat = service.notifierNouvelleVersion("3.0.0");

    assertTrue(resultat.succes());
    assertEquals(200, resultat.statutHttp());
  }

  @Test
  void notifierNouvelleVersion_workerInaccessible_retourneEchecAvecStatut503() {
    final RestClient restClientEnPanne = mock(RestClient.class);
    when(restClientEnPanne.post()).thenThrow(new RuntimeException("connexion refusée"));
    final WorkerProxyService service =
        new WorkerProxyService(restClientEnPanne, new ObjectMapper(), mock(LogAdminService.class));

    final ResultatRelance resultat = service.notifierNouvelleVersion("3.0.0");

    assertFalse(resultat.succes());
    assertEquals(503, resultat.statutHttp());
  }

  @Test
  void relancerFetches_succes_retourneResultatPositif() {
    final RestClient restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    final LogAdminService logAdminService = mock(LogAdminService.class);
    final WorkerProxyService service =
        new WorkerProxyService(restClient, new ObjectMapper(), logAdminService);

    final ResultatRelance resultat = service.relancerFetches();

    assertTrue(resultat.succes());
    assertEquals(200, resultat.statutHttp());
  }
}
