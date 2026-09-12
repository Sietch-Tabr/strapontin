/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
 * Tests unitaires ciblés pour {@link AdminController#revoquerSessions}.
 *
 * @since 2.7.8
 * @author Kwaite
 */
class AdminControllerRevoquerSessionsTest {

  private AdminService adminService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    adminService = mock(AdminService.class);
    controller =
        new AdminController(
            adminService,
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
  void revoquerSessions_utilisateurExistant_retourne200() {
    when(adminService.revoquerToutesSessions("user-1", "admin-1")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.revoquerSessions("user-1", authPour("admin-1"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(adminService).revoquerToutesSessions("user-1", "admin-1");
  }

  @Test
  void revoquerSessions_utilisateurInexistant_retourne404() {
    when(adminService.revoquerToutesSessions("inconnu", "admin-1")).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.revoquerSessions("inconnu", authPour("admin-1"));

    assertEquals(404, reponse.getStatusCode().value());
  }
}
