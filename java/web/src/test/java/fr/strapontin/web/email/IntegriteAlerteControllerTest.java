/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link IntegriteAlerteController}.
 *
 * @since 2.7.3
 * @author Kwaite
 */
class IntegriteAlerteControllerTest {

  private static final String SECRET = "secret-de-test";

  private LogAdminService logAdminService;
  private EmailAlerteService emailAlerteService;
  private IntegriteAlerteController controller;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    emailAlerteService = mock(EmailAlerteService.class);
    controller = new IntegriteAlerteController(emailAlerteService, logAdminService, SECRET);
  }

  @Test
  void signalerStatut_secretInvalide_retourne403() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("ok", "", "Bearer mauvais-secret");

    assertEquals(HttpStatus.FORBIDDEN, reponse.getStatusCode());
    verify(logAdminService, never()).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void signalerStatut_secretAbsentDeLaConfig_retourne403() {
    final IntegriteAlerteController sansSecret =
        new IntegriteAlerteController(emailAlerteService, logAdminService, "");

    final ResponseEntity<Map<String, Object>> reponse =
        sansSecret.signalerStatut("ok", "", "Bearer " + SECRET);

    assertEquals(HttpStatus.FORBIDDEN, reponse.getStatusCode());
  }

  @Test
  void signalerStatut_ok_loggeInfoSansEnvoyerDAlerte() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("ok", "", "Bearer " + SECRET);

    assertEquals(HttpStatus.OK, reponse.getStatusCode());
    verify(logAdminService).logAdmin(eq("info"), eq("web"), anyString(), anyMap());
    verify(emailAlerteService, never()).envoyerAlerteIntegrite(anyString());
  }

  @Test
  void signalerStatut_modifie_loggeErrorEtEnvoieAlerte() {
    final ResponseEntity<Map<String, Object>> reponse =
        controller.signalerStatut("modifie", "web.jar : md5 modifié", "Bearer " + SECRET);

    assertEquals(HttpStatus.OK, reponse.getStatusCode());
    verify(logAdminService).logAdmin(eq("error"), eq("web"), anyString(), anyMap());
    verify(emailAlerteService).envoyerAlerteIntegrite("web.jar : md5 modifié");
  }
}
