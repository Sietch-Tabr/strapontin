/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.email.EmailServiceAdmin;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires ciblés pour {@link AdminController#creerUtilisateur} — le lien d'invitation ne
 * doit plus jamais être construit depuis le header {@code Host} de la requête entrante quand
 * {@code PUBLIC_BASE_URL} n'est pas configurée (Host Header Injection).
 *
 * @since 2.8.2
 * @author Kwaite
 */
class AdminControllerCreerUtilisateurTest {

  private AdminService adminService;
  private EmailService emailService;
  private ConfigService configService;
  private AdminController controller;

  @BeforeEach
  void setUp() {
    adminService = mock(AdminService.class);
    emailService = mock(EmailService.class);
    configService = mock(ConfigService.class);
    controller =
        new AdminController(
            adminService,
            mock(ConfigAdminService.class),
            mock(JobsAdminService.class),
            mock(BanniereService.class),
            mock(WorkerProxyService.class),
            emailService,
            mock(EmailServiceAdmin.class),
            mock(VapidService.class),
            configService);
  }

  private static Authentication authPour(final String userId) {
    final UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "admin", false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void creerUtilisateur_publicBaseUrlConfiguree_appelleLeServiceAvecCetteUrl() {
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(emailService.estDisponible()).thenReturn(true);
    when(adminService.creerUtilisateur(eq("alice@exemple.fr"), eq("admin-1"), anyString()))
        .thenReturn(Optional.of(new ResultatCreation("alice@exemple.fr", "alice123", null)));

    final ResponseEntity<Map<String, Object>> reponse =
        controller.creerUtilisateur(
            new RequeteCreerUtilisateur("alice@exemple.fr"), authPour("admin-1"));

    assertEquals(201, reponse.getStatusCode().value());
    verify(adminService).creerUtilisateur("alice@exemple.fr", "admin-1", "https://strapontin.fr");
  }

  @Test
  void creerUtilisateur_publicBaseUrlAbsente_retourne503SansAppelerLeService() {
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.empty());

    final ResponseEntity<Map<String, Object>> reponse =
        controller.creerUtilisateur(
            new RequeteCreerUtilisateur("alice@exemple.fr"), authPour("admin-1"));

    assertEquals(503, reponse.getStatusCode().value());
    verifyNoInteractions(adminService);
  }
}
