/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.utilisateur.UserController;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link TotpEnrollmentController}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class TotpEnrollmentControllerTest {

  private static final String SESSION_TOTP_SETUP = "TOTP_SECRET_SETUP";

  @Mock private TotpService totpService;
  @Mock private UserController userController;
  @Mock private LogAdminService logAdminService;
  @Mock private HttpSession session;

  private TotpEnrollmentController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new TotpEnrollmentController(totpService, userController, logAdminService);
    auth = mock(Authentication.class);
  }

  private void stubPrincipal() {
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void setup_secretDejaEnSession_reutiliseLeMemeSecret() {
    stubPrincipal();
    when(session.getAttribute(SESSION_TOTP_SETUP)).thenReturn("SECRETEXISTANT");
    when(totpService.genererUriOtpAuth("SECRETEXISTANT", "kwaite")).thenReturn("otpauth://uri");
    when(totpService.genererQrCodeSvg("otpauth://uri")).thenReturn("<svg/>");

    ResponseEntity<Map<String, Object>> rep = controller.setup(session, auth);

    assertEquals("SECRETEXISTANT", rep.getBody().get("totpSecret"));
    verify(totpService, never()).genererSecret();
    verify(session, never()).setAttribute(eq(SESSION_TOTP_SETUP), anyString());
  }

  @Test
  void setup_aucunSecretEnSession_enGenereUnNouveauEtLeStocke() {
    stubPrincipal();
    when(session.getAttribute(SESSION_TOTP_SETUP)).thenReturn(null);
    when(totpService.genererSecret()).thenReturn("NOUVEAUSECRET");
    when(totpService.genererUriOtpAuth("NOUVEAUSECRET", "kwaite")).thenReturn("otpauth://uri");
    when(totpService.genererQrCodeSvg("otpauth://uri")).thenReturn("<svg/>");

    ResponseEntity<Map<String, Object>> rep = controller.setup(session, auth);

    assertEquals("NOUVEAUSECRET", rep.getBody().get("totpSecret"));
    verify(session).setAttribute(SESSION_TOTP_SETUP, "NOUVEAUSECRET");
  }

  @Test
  void confirmer_sessionExpiree_retourne400() {
    when(session.getAttribute(SESSION_TOTP_SETUP)).thenReturn(null);

    ResponseEntity<Map<String, Object>> rep =
        controller.confirmer(new TotpChallengeRequest("123456"), session, auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(userController, never()).activerTotp(anyString(), anyString());
  }

  @Test
  void confirmer_codeIncorrect_retourne400() {
    when(session.getAttribute(SESSION_TOTP_SETUP)).thenReturn("SECRET");
    when(totpService.verifierCode("SECRET", "000000")).thenReturn(false);

    ResponseEntity<Map<String, Object>> rep =
        controller.confirmer(new TotpChallengeRequest("000000"), session, auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(userController, never()).activerTotp(anyString(), anyString());
  }

  @Test
  void confirmer_codeCorrect_activeLeTotpEtPurgeLaSession() {
    stubPrincipal();
    when(session.getAttribute(SESSION_TOTP_SETUP)).thenReturn("SECRET");
    when(totpService.verifierCode("SECRET", "123456")).thenReturn(true);

    ResponseEntity<Map<String, Object>> rep =
        controller.confirmer(new TotpChallengeRequest("123456"), session, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(userController).activerTotp("user-1", "SECRET");
    verify(session).removeAttribute(SESSION_TOTP_SETUP);
    verify(logAdminService)
        .logAdmin(eq("info"), eq("web"), anyString(), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void desactiver_totpNonActive_secretNullEtCodeRefuse_retourne400() {
    stubPrincipal();
    when(userController.lireSecretTotp("user-1")).thenReturn(null);
    when(totpService.verifierCode(null, "123456")).thenReturn(false);

    ResponseEntity<Map<String, Object>> rep = controller.desactiver(new TotpChallengeRequest("123456"), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(userController, never()).desactiverTotp(anyString());
  }

  @Test
  void desactiver_codeCorrect_desactiveLeTotp() {
    stubPrincipal();
    when(userController.lireSecretTotp("user-1")).thenReturn("SECRET");
    when(totpService.verifierCode("SECRET", "123456")).thenReturn(true);

    ResponseEntity<Map<String, Object>> rep = controller.desactiver(new TotpChallengeRequest("123456"), auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(userController).desactiverTotp("user-1");
  }
}
