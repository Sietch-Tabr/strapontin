/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.EmailServiceAdmin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link SystemeAdminController#genererVapid} (le résultat de {@link
 * VapidService#genererVapid()} doit être exposé dans la réponse, pas seulement loggé).
 *
 * @since 2.6.5
 * @author Kwaite
 */
class AdminControllerGenererVapidTest {

  private VapidService vapidService;
  private SystemeAdminController controller;

  @BeforeEach
  void setUp() {
    vapidService = mock(VapidService.class);
    controller =
        new SystemeAdminController(
            mock(AdminService.class),
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            mock(WorkerProxyService.class),
            mock(EmailServiceAdmin.class),
            vapidService,
            mock(BuildProperties.class));
  }

  @Test
  void genererVapid_succes_exposeClePubliqueEtNbAbonnementsSupprimes() throws Exception {
    when(vapidService.genererVapid("Fuite de la clé privée"))
        .thenReturn(new VapidService.ResultatVapid("cle-pub", 3, 2, 1));

    final ResponseEntity<Map<String, Object>> reponse =
        controller.genererVapid(new RequeteVapid("Fuite de la clé privée"));

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("cle-pub", reponse.getBody().get("clePublique"));
    assertEquals(3, reponse.getBody().get("nbAbonnementsSupprimes"));
    assertEquals(2, reponse.getBody().get("nbComptesPrevenus"));
    assertEquals(1, reponse.getBody().get("nbComptesNonPrevenus"));
  }

  @Test
  void genererVapid_exceptionService_retourne500() throws Exception {
    when(vapidService.genererVapid("Fuite de la clé privée"))
        .thenThrow(new RuntimeException("erreur génération clé"));

    final ResponseEntity<Map<String, Object>> reponse =
        controller.genererVapid(new RequeteVapid("Fuite de la clé privée"));

    assertEquals(500, reponse.getStatusCode().value());
  }
}
