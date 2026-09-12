/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link AdminController#publierBanniere} et {@link
 * AdminController#effacerBanniere}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
class AdminControllerBanniereTest {

  private BanniereService banniereService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    banniereService = mock(BanniereService.class);
    controller =
        new AdminController(
            mock(AdminService.class),
            mock(AdminRevocationService.class),
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            banniereService,
            mock(WorkerProxyService.class),
            mock(EmailService.class),
            mock(EmailServiceAdmin.class),
            mock(VapidService.class),
            mock(ConfigService.class));
  }

  @Test
  void publierBanniere_typeValide_delegueAuServiceAvecMessageStrippe() {
    final ResponseEntity<Map<String, Object>> rep =
        controller.publierBanniere(new RequeteBanniere("  Maintenance  ", "warning"));

    assertEquals(200, rep.getStatusCode().value());
    verify(banniereService).publierBanniere("Maintenance", "warning");
  }

  @Test
  void publierBanniere_typeInvalide_replieSurInfo() {
    controller.publierBanniere(new RequeteBanniere("Maintenance", "pas-un-type"));

    verify(banniereService).publierBanniere("Maintenance", "info");
  }

  @Test
  void effacerBanniere_delegueAuService() {
    final ResponseEntity<Map<String, Object>> rep = controller.effacerBanniere();

    assertEquals(200, rep.getStatusCode().value());
    verify(banniereService).effacerBanniere();
  }
}
