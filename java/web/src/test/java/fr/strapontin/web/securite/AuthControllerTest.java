/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;

/// Tests unitaires pour {@link AuthController} — challenge TOTP et réinitialisation de mot de
// passe.
///
/// @since 2.2.5
/// @author Kwaite
class AuthControllerTest {

  private AuthController authController;
  private AuthService authService;
  private TotpService totpService;
  private NamedParameterJdbcTemplate jdbc;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    authService = mock(AuthService.class);
    totpService = mock(TotpService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logAdminService = mock(LogAdminService.class);
    authController =
        new AuthController(
            mock(AuthenticationManager.class),
            authService,
            totpService,
            jdbc,
            mock(ConfigService.class),
            logAdminService);
  }

  // --- verifierTotp ---

  @Test
  void verifierTotp_sansSession_retourne409() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(409, reponse.getStatusCode().value());
  }

  @Test
  void verifierTotp_sansChallengeEnAttente_retourne409() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(null);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(409, reponse.getStatusCode().value());
  }

  @Test
  void verifierTotp_sansAuthentification_retourne401() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_codeCorrect_retourne200() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("SECRETBASE32"));
    when(totpService.verifierCode("SECRETBASE32", "123456")).thenReturn(true);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, auth);

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_codeIncorrect_retourne422() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);
    when(session.getAttribute("TOTP_ECHECS")).thenReturn(null);

    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("SECRETBASE32"));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(422, reponse.getStatusCode().value());
    verify(session).setAttribute("TOTP_ECHECS", 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_tropDEchecs_invalideSessionEtRetourne401() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);
    when(session.getAttribute("TOTP_ECHECS")).thenReturn(4);

    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("SECRETBASE32"));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  // --- reinitialiserMotDePasse ---

  @Test
  void reinitialiserMotDePasse_tokenNull_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest(null, "motdepasse12345", null),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_motDePasseNull_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", null, null), mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_motDePasseTropCourt_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "court", null), mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantTropCourt_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "motdepasse12345", "ab"),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantTropLong_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "motdepasse12345", "a".repeat(31)),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantFormatInvalide_retourne400() {
    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "motdepasse12345", "alice:test"),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantAvecPointEtTiret_passeLaValidationFormat() {
    // Si le format est valide, activerCompteInvite est appelé (pas un retour 400 format).
    // On simule un token invalide pour vérifier que la validation format ne bloque pas.
    doThrow(new RuntimeException("token simulé invalide"))
        .when(authService)
        .activerCompteInvite(anyString(), anyString(), anyString());
    assertThrows(
        RuntimeException.class,
        () ->
            authController.reinitialiserMotDePasse(
                new ResetPasswordRequest("token", "motdepasse12345", "alice.dupont-2"),
                mock(HttpServletRequest.class)),
        "alice.dupont-2 doit passer la validation de format et atteindre activerCompteInvite");
  }

  // --- logout ---

  @Test
  void logout_sansSession_retourne204() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getSession(false)).thenReturn(null);

    var reponse = authController.logout(req, resp);

    assertEquals(204, reponse.getStatusCode().value());
  }

  @Test
  void logout_cookieSuppression_aFlagsSecureEtSameSite() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getSession(false)).thenReturn(null);

    authController.logout(req, resp);

    ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
    verify(resp).addCookie(cookieCaptor.capture());
    Cookie cookie = cookieCaptor.getValue();
    assertEquals("SESSION", cookie.getName());
    assertEquals(0, cookie.getMaxAge());
    assertTrue(cookie.isHttpOnly());
    assertTrue(cookie.getSecure());
    assertEquals("Lax", cookie.getAttribute("SameSite"));
  }

  @Test
  void reinitialiserMotDePasse_resetClassique_retourne200() {
    doNothing().when(authService).reinitialiserMotDePasse(anyString(), anyString());

    var reponse =
        authController.reinitialiserMotDePasse(
            new ResetPasswordRequest("tokenvalide", "motdepasse12345", null),
            mock(HttpServletRequest.class));

    assertEquals(200, reponse.getStatusCode().value());
  }
}
