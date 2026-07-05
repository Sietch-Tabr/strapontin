/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/// Tests unitaires pour {@link AdminController#genererVapid} (#851 — le résultat de
/// {@link VapidService#genererVapid()} doit être exposé dans la réponse, pas seulement loggé).
///
/// @since 2.6.5
/// @author Kwaite
class AdminControllerGenererVapidTest {

  private VapidService vapidService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    vapidService = mock(VapidService.class);
    controller =
        new AdminController(
            mock(AdminService.class),
            mock(BanniereService.class),
            mock(WorkerProxyService.class),
            mock(EmailService.class),
            vapidService,
            mock(ConfigService.class));
  }

  @Test
  void genererVapid_succes_exposeClePubliqueEtNbAbonnementsSupprimes() throws Exception {
    when(vapidService.genererVapid()).thenReturn(new VapidService.ResultatVapid("cle-pub", 3));

    ResponseEntity<Map<String, Object>> reponse = controller.genererVapid();

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("cle-pub", reponse.getBody().get("clePublique"));
    assertEquals(3, reponse.getBody().get("nbAbonnementsSupprimes"));
  }

  @Test
  void genererVapid_exceptionService_retourne500() throws Exception {
    when(vapidService.genererVapid()).thenThrow(new RuntimeException("erreur génération clé"));

    ResponseEntity<Map<String, Object>> reponse = controller.genererVapid();

    assertEquals(500, reponse.getStatusCode().value());
  }
}
