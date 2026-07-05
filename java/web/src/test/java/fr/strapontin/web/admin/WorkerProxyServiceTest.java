/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link WorkerProxyService} — focus sur {@code relancerJob} (v2.6.0,
/// #706/#710, bouton « Relancer » par job de maintenance).
///
/// @since 2.6.0
/// @author Kwaite
class WorkerProxyServiceTest {

  @Test
  void relancerJob_workerInaccessible_retourneEchecAvecStatut503() {
    RestClient restClientEnPanne = mock(RestClient.class);
    when(restClientEnPanne.post()).thenThrow(new RuntimeException("connexion refusée"));
    LogAdminService logAdminService = mock(LogAdminService.class);
    WorkerProxyService service =
        new WorkerProxyService(restClientEnPanne, new ObjectMapper(), logAdminService);

    ResultatRelance resultat = service.relancerJob("purger:salles");

    assertFalse(resultat.succes());
    assertNotNull(resultat.messageErreur());
    // #837 : une exception réseau générique (pas de réponse HTTP du tout) reste un vrai 503 —
    // distinct des cas où le worker répond mais avec un statut d'erreur (400/5xx).
    assertEquals(503, resultat.statutHttp());
  }
}
