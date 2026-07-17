/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/// Tests unitaires ciblés pour {@link AdminController#reinitialiserMdp} (#815) — filet de
/// secours {@code lienReset} quand Resend n'est pas disponible, symétrique à
/// {@link AdminController#creerUtilisateur}.
///
/// @since 2.6.4
/// @author Kwaite
class AdminControllerReinitialiserMdpTest {

  private AdminService adminService;
  private EmailService emailService;
  private ConfigService configService;
  private AdminController controller;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    adminService = mock(AdminService.class);
    emailService = mock(EmailService.class);
    configService = mock(ConfigService.class);
    request = mock(HttpServletRequest.class);
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    controller =
        new AdminController(
            adminService,
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            mock(WorkerProxyService.class),
            emailService,
            mock(VapidService.class),
            configService);
  }

  private static Authentication authPour(String userId) {
    UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "admin", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void reinitialiserMdp_resendIndisponible_exposeLienReset() {
    when(emailService.estDisponible()).thenReturn(false);
    when(adminService.reinitialiserMdp(eq("user-2"), eq("admin-1"), anyString()))
        .thenReturn(
            Optional.of(new ResultatReset("user@exemple.fr", "https://strapontin.fr/reset")));

    ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserMdp("user-2", authPour("admin-1"), request);

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("https://strapontin.fr/reset", reponse.getBody().get("lienReset"));
  }

  @Test
  void reinitialiserMdp_resendDisponible_nexposePasLienReset() {
    when(emailService.estDisponible()).thenReturn(true);
    when(adminService.reinitialiserMdp(eq("user-2"), eq("admin-1"), anyString()))
        .thenReturn(
            Optional.of(new ResultatReset("user@exemple.fr", "https://strapontin.fr/reset")));

    ResponseEntity<Map<String, Object>> reponse =
        controller.reinitialiserMdp("user-2", authPour("admin-1"), request);

    assertEquals(200, reponse.getStatusCode().value());
    assertFalse(reponse.getBody().containsKey("lienReset"));
  }
}
