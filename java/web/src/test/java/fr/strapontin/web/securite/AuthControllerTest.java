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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;

/// Tests unitaires pour {@link AuthController} — challenge TOTP et réinitialisation de mot de
/// passe.
///
/// @since 2.2.5
/// @author Kwaite
class AuthControllerTest {

  private AuthController authController;
  private AuthenticationManager authenticationManager;
  private AuthService authService;
  private TotpService totpService;
  private NamedParameterJdbcTemplate jdbc;
  private ConfigService configService;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    authenticationManager = mock(AuthenticationManager.class);
    authService = mock(AuthService.class);
    totpService = mock(TotpService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    authController =
        new AuthController(
            authenticationManager, authService, totpService, jdbc, configService, logAdminService);
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

    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 0));
    when(totpService.verifierCode("SECRETBASE32", "123456")).thenReturn(true);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, auth);

    assertEquals(200, reponse.getStatusCode().value());
    verify(jdbc).update(contains("totp_failure_count = 0"), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_codeIncorrect_retourne422() {
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

    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 0));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(422, reponse.getStatusCode().value());
    verify(jdbc).update(contains("totp_failure_count + 1"), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_tropDEchecs_invalideSessionEtRetourne401() {
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

    // 4 échecs déjà enregistrés → le 5e déclenche le verrouillage
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 4));
    when(totpService.verifierCode("SECRETBASE32", "000000")).thenReturn(false);

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  @Test
  @SuppressWarnings("unchecked")
  void verifierTotp_compteurDejaVerrouille_invalideSessionEtRetourne401() {
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

    // Compte déjà verrouillé en base (≥ MAX_ECHECS_TOTP) — aucun code ne doit être vérifié
    when(jdbc.queryForMap(anyString(), anyMap()))
        .thenReturn(Map.of("two_factor_secret", "SECRETBASE32", "totp_failure_count", 5));

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
    verify(totpService, never()).verifierCode(anyString(), anyString());
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

  // --- login ---

  @Test
  void login_credentialsValides_retourneUtilisateurEtOuvreSession() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(true)).thenReturn(session);

    var reponse = authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("alice", reponse.getBody().identifiant());
    assertFalse(reponse.getBody().totpRequis());
    verify(session, never()).setAttribute(eq(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE), any());
  }

  @Test
  void login_sessionExistante_lInvalideAvantDEnCreerUneNouvelle() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession ancienneSession = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(ancienneSession);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    verify(ancienneSession).invalidate();
  }

  @Test
  void login_totpActif_positionneSessionTotpEnAttente() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(true)).thenReturn(session);

    var reponse = authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    assertTrue(reponse.getBody().totpRequis());
    verify(session).setAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
  }

  @Test
  void login_principalInattendu_leveIllegalStateException() {
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn("pas-un-utilisateur-details");
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    assertThrows(
        IllegalStateException.class,
        () -> authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest));
  }

  // --- moi ---

  @Test
  void moi_nonAuthentifie_retourne401() {
    var reponse = authController.moi(null, mock(HttpServletRequest.class));

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  void moi_principalPasUtilisateurDetails_retourne401() {
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn("anonymousUser");

    var reponse = authController.moi(auth, mock(HttpServletRequest.class));

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  void moi_authentifieAvecChallengeTotpEnAttente_retourneTotpRequisVrai() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    var reponse = authController.moi(auth, httpRequest);

    assertEquals(200, reponse.getStatusCode().value());
    assertTrue(reponse.getBody().totpRequis());
  }

  @Test
  void moi_authentifieSansSession_retourneTotpRequisFaux() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);

    var reponse = authController.moi(auth, httpRequest);

    assertFalse(reponse.getBody().totpRequis());
  }

  // --- motDePasseOublie (endpoint) ---

  @Test
  void motDePasseOublieEndpoint_emailBlanc_neDelegueRienEtRetourne200() {
    var reponse =
        authController.motDePasseOublie(
            new ForgotPasswordRequest(""), mock(HttpServletRequest.class));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService, never()).motDePasseOublie(anyString(), anyString());
  }

  @Test
  void motDePasseOublieEndpoint_emailPresent_normaliseEtDelegueAuService() {
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(java.util.Optional.of("https://strapontin.fr"));
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    var reponse =
        authController.motDePasseOublie(new ForgotPasswordRequest(" Alice@Test.FR "), httpRequest);

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService).motDePasseOublie("alice@test.fr", "https://strapontin.fr");
  }

  // --- typeToken ---

  @Test
  void typeToken_tokenInvitation_retourneTypeInvite() {
    when(authService.estTokenInvite("token")).thenReturn(true);

    var reponse = authController.typeToken("token");

    assertEquals("invite", reponse.getBody().get("type"));
  }

  @Test
  void typeToken_tokenResetOuInconnu_retourneTypeReset() {
    when(authService.estTokenInvite("token")).thenReturn(false);

    var reponse = authController.typeToken("token");

    assertEquals("reset", reponse.getBody().get("type"));
  }
}
