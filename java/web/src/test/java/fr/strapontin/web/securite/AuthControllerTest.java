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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires pour {@link AuthController} — challenge TOTP et réinitialisation de mot de
 * passe.
 *
 * <p>Depuis, le compteur d'échecs/verrouillage TOTP est délégué à {@link TotpChallengeService}
 * (testé séparément dans {@code TotpChallengeServiceTest}) — ce fichier vérifie uniquement la
 * traduction HTTP de chaque {@link ResultatTotp}.
 *
 * @since 2.2.5
 * @author Kwaite
 */
class AuthControllerTest {

  private AuthController authController;
  private AuthenticationManager authenticationManager;
  private AuthService authService;
  private TotpChallengeService totpChallengeService;
  private ConfigService configService;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    authenticationManager = mock(AuthenticationManager.class);
    authService = mock(AuthService.class);
    totpChallengeService = mock(TotpChallengeService.class);
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    authController =
        new AuthController(
            authenticationManager,
            authService,
            totpChallengeService,
            configService,
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
    when(totpChallengeService.verifier("u1", "alice", "123456", "127.0.0.1"))
        .thenReturn(ResultatTotp.succes());

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, auth);

    assertEquals(200, reponse.getStatusCode().value());
    verify(session).removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
  }

  @Test
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
    when(totpChallengeService.verifier("u1", "alice", "000000", "127.0.0.1"))
        .thenReturn(ResultatTotp.echec(3));

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(422, reponse.getStatusCode().value());
    assertEquals("Code incorrect. 3 essai(s) restant(s).", reponse.getBody().get("erreur"));
  }

  @Test
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
    when(totpChallengeService.verifier("u1", "alice", "000000", "127.0.0.1"))
        .thenReturn(ResultatTotp.verrouilleMaintenant());

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  @Test
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
    when(totpChallengeService.verifier("u1", "alice", "000000", "127.0.0.1"))
        .thenReturn(ResultatTotp.dejaVerrouille());

    var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  // --- reinitialiserMotDePasse ---
  // token/motDePasse manquants ou trop courts : voir ResetPasswordRequestValidationTest
  // (@Valid ne s'exerce pas sur un appel Java direct au contrôleur, seulement via Spring MVC).

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

  @Test
  void reinitialiserMotDePasse_activationInvitation_peupleWebAuthenticationDetailsSurLeToken() {
    when(authService.activerCompteInvite("token", "alice", "motdepasse12345")).thenReturn("alice");
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    authController.reinitialiserMotDePasse(
        new ResetPasswordRequest("token", "motdepasse12345", "alice"), httpRequest);

    ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
        ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());
    assertNotNull(tokenCaptor.getValue().getDetails());
    assertInstanceOf(
        org.springframework.security.web.authentication.WebAuthenticationDetails.class,
        tokenCaptor.getValue().getDetails());
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
  void login_credentialsValides_peupleWebAuthenticationDetailsSurLeToken() {
    UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
        ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());
    assertNotNull(tokenCaptor.getValue().getDetails());
    assertInstanceOf(
        org.springframework.security.web.authentication.WebAuthenticationDetails.class,
        tokenCaptor.getValue().getDetails());
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
    var reponse = authController.motDePasseOublie(new ForgotPasswordRequest(""));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService, never()).motDePasseOublie(anyString(), anyString());
  }

  @Test
  void motDePasseOublieEndpoint_emailPresent_normaliseEtDelegueAuService() {
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(java.util.Optional.of("https://strapontin.fr"));

    var reponse = authController.motDePasseOublie(new ForgotPasswordRequest(" Alice@Test.FR "));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService).motDePasseOublie("alice@test.fr", "https://strapontin.fr");
  }

  // PUBLIC_BASE_URL absente : ne doit jamais retomber sur le header Host de la requête entrante
  // (Host Header Injection) — le lien de réinitialisation ne doit tout simplement pas être
  // envoyé, avec une alerte logs_admin plutôt qu'un lien potentiellement redirigé vers un domaine
  // contrôlé par un attaquant.
  @Test
  void motDePasseOublieEndpoint_baseUrlNonConfiguree_neDelegueRienEtAlerte() {
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(java.util.Optional.empty());

    var reponse = authController.motDePasseOublie(new ForgotPasswordRequest("alice@test.fr"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService, never()).motDePasseOublie(anyString(), anyString());
    verify(logAdminService).logAdmin(eq("error"), eq("auth"), anyString());
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
