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
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires ciblés pour {@link AdminController#verrouillerCompte} et {@link
 * AdminController#deverrouillerCompte}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class AdminControllerVerrouillerCompteTest {

  private AdminService adminService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    adminService = mock(AdminService.class);
    controller =
        new AdminController(
            adminService,
            mock(AdminRevocationService.class),
            mock(EmailService.class),
            mock(ConfigService.class));
  }

  private static Authentication authPour(final String userId) {
    final UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "admin", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void verrouillerCompte_utilisateurExistant_retourne200() {
    when(adminService.verrouillerCompte("user-1", "admin-1")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.verrouillerCompte("user-1", authPour("admin-1"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(adminService).verrouillerCompte("user-1", "admin-1");
  }

  @Test
  void verrouillerCompte_utilisateurInexistant_retourne404() {
    when(adminService.verrouillerCompte("inconnu", "admin-1")).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.verrouillerCompte("inconnu", authPour("admin-1"));

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void verrouillerCompte_propreCompte_retourne400SansAppelerLeService() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.verrouillerCompte("admin-1", authPour("admin-1"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(adminService, never()).verrouillerCompte("admin-1", "admin-1");
  }

  @Test
  void deverrouillerCompte_utilisateurExistant_retourne200() {
    when(adminService.deverrouillerCompte("user-1", "admin-1")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.deverrouillerCompte("user-1", authPour("admin-1"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(adminService).deverrouillerCompte("user-1", "admin-1");
  }

  @Test
  void deverrouillerCompte_utilisateurInexistant_retourne404() {
    when(adminService.deverrouillerCompte("inconnu", "admin-1")).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse =
        controller.deverrouillerCompte("inconnu", authPour("admin-1"));

    assertEquals(404, reponse.getStatusCode().value());
  }
}
