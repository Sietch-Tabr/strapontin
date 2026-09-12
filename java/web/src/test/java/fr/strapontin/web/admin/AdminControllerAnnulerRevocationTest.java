/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires ciblés pour {@link AdminController#annulerRevocationAdmin} — seul endpoint de
 * gestion utilisateur à ne pas vérifier {@code userId.equals(adminId)} avant.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class AdminControllerAnnulerRevocationTest {

  private AdminRevocationService adminRevocationService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    adminRevocationService = mock(AdminRevocationService.class);
    controller =
        new AdminController(
            mock(AdminService.class),
            adminRevocationService,
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            mock(WorkerProxyService.class),
            mock(EmailService.class),
            mock(EmailServiceAdmin.class),
            mock(VapidService.class),
            mock(ConfigService.class));
  }

  private static Authentication authPour(final String userId) {
    final UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "admin", false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void annulerRevocationAdmin_autoAnnulation_retourne400SansAppelerLeService() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.annulerRevocationAdmin("admin-1", authPour("admin-1"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(adminRevocationService, never()).annulerRevocationAdmin("admin-1", "admin-1");
  }

  @Test
  void annulerRevocationAdmin_autreAdmin_delegueAuService() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.annulerRevocationAdmin("admin-2", authPour("admin-1"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(adminRevocationService).annulerRevocationAdmin("admin-2", "admin-1");
  }
}
