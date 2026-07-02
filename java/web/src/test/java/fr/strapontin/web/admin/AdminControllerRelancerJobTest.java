/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/// Tests unitaires ciblés pour {@link AdminController#relancerJob} (v2.6.0, #706/#710) — bouton
/// « Relancer » par job de maintenance dans l'onglet admin Tableau de bord.
///
/// @since 2.6.0
/// @author Kwaite
class AdminControllerRelancerJobTest {

  private WorkerProxyService workerProxyService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    workerProxyService = mock(WorkerProxyService.class);
    controller =
        new AdminController(
            mock(AdminService.class),
            mock(BanniereService.class),
            workerProxyService,
            mock(EmailService.class),
            mock(VapidService.class),
            mock(ConfigService.class));
  }

  @Test
  void relancerJob_succes_retourne200() {
    when(workerProxyService.relancerJob("purger:salles"))
        .thenReturn(new ResultatRelance(true, null));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("purger:salles");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("déclenché", reponse.getBody().get("statut"));
  }

  @Test
  void relancerJob_workerInaccessible_retourne503() {
    when(workerProxyService.relancerJob("reconciliation:nocturne"))
        .thenReturn(new ResultatRelance(false, "Worker inaccessible."));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("reconciliation:nocturne");

    assertEquals(503, reponse.getStatusCode().value());
  }
}
