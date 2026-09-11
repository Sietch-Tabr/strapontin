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

/**
 * Tests unitaires ciblés pour {@link AdminController#relancerJob} (v2.6.0) — bouton
 * « Relancer » par job de maintenance dans l'onglet admin Tableau de bord.
 *
 * @since 2.6.0
 * @author Kwaite
 */
class AdminControllerRelancerJobTest {

  private WorkerProxyService workerProxyService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    workerProxyService = mock(WorkerProxyService.class);
    controller =
        new AdminController(
            mock(AdminService.class),
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            workerProxyService,
            mock(EmailService.class),
            mock(VapidService.class),
            mock(ConfigService.class));
  }

  @Test
  void relancerJob_succes_retourne200() {
    when(workerProxyService.relancerJob("purger:salles"))
        .thenReturn(new ResultatRelance(true, null, 200));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("purger:salles");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("déclenché", reponse.getBody().get("statut"));
  }

  @Test
  void relancerJob_workerInaccessible_retourne503() {
    when(workerProxyService.relancerJob("reconciliation:nocturne"))
        .thenReturn(new ResultatRelance(false, "Worker inaccessible.", 503));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("reconciliation:nocturne");

    assertEquals(503, reponse.getStatusCode().value());
  }

  // un typeJob inconnu (erreur cliente 400 renvoyée par le worker) ne doit plus être
  // masqué sous un 503 générique — l'admin doit pouvoir distinguer une faute de frappe d'une
  // vraie panne du worker.
  @Test
  void relancerJob_typeJobInconnu_retourne400() {
    when(workerProxyService.relancerJob("job:inexistant"))
        .thenReturn(new ResultatRelance(false, "Job inconnu : job:inexistant.", 400));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("job:inexistant");

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void relancerJob_erreurInterneWorker_retourne502() {
    when(workerProxyService.relancerJob("reconciliation:nocturne"))
        .thenReturn(new ResultatRelance(false, "Erreur interne worker (statut 500).", 502));

    ResponseEntity<Map<String, Object>> reponse = controller.relancerJob("reconciliation:nocturne");

    assertEquals(502, reponse.getStatusCode().value());
  }
}
