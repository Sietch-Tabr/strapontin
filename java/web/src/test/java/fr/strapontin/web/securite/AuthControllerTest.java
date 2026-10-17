/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Tests unitaires pour {@link AuthController} — login, logout, challenge TOTP, utilisateur courant.
 *
 * <p>Depuis, le compteur d'échecs/verrouillage TOTP est délégué à {@link TotpChallengeService}
 * (testé séparément dans {@code TotpChallengeServiceTest}) — ce fichier vérifie uniquement la
 * traduction HTTP de chaque {@link ResultatTotp}. La réinitialisation de mot de passe est testée
 * dans {@code PasswordResetControllerTest}.
 *
 * @since 2.2.5
 * @author Kwaite
 */
class AuthControllerTest {

  private AuthController authController;
  private AuthenticationManager authenticationManager;
  private TotpChallengeService totpChallengeService;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    authenticationManager = mock(AuthenticationManager.class);
    totpChallengeService = mock(TotpChallengeService.class);
    logAdminService = mock(LogAdminService.class);
    authController =
        new AuthController(authenticationManager, totpChallengeService, logAdminService);
  }

  // --- verifierTotp ---

  @Test
  void verifierTotp_sansSession_retourne409() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(409, reponse.getStatusCode().value());
  }

  @Test
  void verifierTotp_sansChallengeEnAttente_retourne409() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(null);

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(409, reponse.getStatusCode().value());
  }

  @Test
  void verifierTotp_sansAuthentification_retourne401() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, null);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  void verifierTotp_codeCorrect_retourne200() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);
    when(totpChallengeService.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "123456", "127.0.0.1")))
        .thenReturn(ResultatTotp.succes());

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("123456"), httpRequest, auth);

    assertEquals(200, reponse.getStatusCode().value());
    verify(session).removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
  }

  @Test
  void verifierTotp_codeIncorrect_retourne422() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);
    when(totpChallengeService.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1")))
        .thenReturn(ResultatTotp.echec(3));

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(422, reponse.getStatusCode().value());
    assertEquals("Code incorrect. 3 essai(s) restant(s).", reponse.getBody().get("erreur"));
  }

  @Test
  void verifierTotp_tropDEchecs_invalideSessionEtRetourne401() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);
    when(totpChallengeService.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1")))
        .thenReturn(ResultatTotp.verrouilleMaintenant());

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  @Test
  void verifierTotp_compteurDejaVerrouille_invalideSessionEtRetourne401() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);
    when(totpChallengeService.verifier(
            new TotpChallengeService.ChallengeTotp("u1", "alice", "000000", "127.0.0.1")))
        .thenReturn(ResultatTotp.dejaVerrouille());

    final var reponse =
        authController.verifierTotp(new TotpChallengeRequest("000000"), httpRequest, auth);

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
    verify(session).invalidate();
  }

  // --- logout ---

  @Test
  void logout_sansSession_retourne204() {
    final HttpServletRequest req = mock(HttpServletRequest.class);
    final HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getSession(false)).thenReturn(null);

    final var reponse = authController.logout(req, resp);

    assertEquals(204, reponse.getStatusCode().value());
  }

  @Test
  void logout_cookieSuppression_aFlagsSecureEtSameSite() {
    final HttpServletRequest req = mock(HttpServletRequest.class);
    final HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getSession(false)).thenReturn(null);

    authController.logout(req, resp);

    final ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
    verify(resp).addCookie(cookieCaptor.capture());
    final Cookie cookie = cookieCaptor.getValue();
    assertEquals("SESSION", cookie.getName());
    assertEquals(0, cookie.getMaxAge());
    assertTrue(cookie.isHttpOnly());
    assertTrue(cookie.getSecure());
    assertEquals("Lax", cookie.getAttribute("SameSite"));
  }

  // --- login ---

  @Test
  void login_credentialsValides_retourneUtilisateurEtOuvreSession() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(true)).thenReturn(session);

    final var reponse =
        authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals("alice", reponse.getBody().identifiant());
    assertFalse(reponse.getBody().totpRequis());
    verify(session, never()).setAttribute(eq(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE), any());
  }

  @Test
  void login_sessionExistante_lInvalideAvantDEnCreerUneNouvelle() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession ancienneSession = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(ancienneSession);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    verify(ancienneSession).invalidate();
  }

  @Test
  void login_totpActif_positionneSessionTotpEnAttente() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(true)).thenReturn(session);

    final var reponse =
        authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    assertTrue(reponse.getBody().totpRequis());
    verify(session).setAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
  }

  @Test
  void login_credentialsValides_peupleWebAuthenticationDetailsSurLeToken() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest);

    final ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
        ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());
    assertNotNull(tokenCaptor.getValue().getDetails());
    assertInstanceOf(WebAuthenticationDetails.class, tokenCaptor.getValue().getDetails());
  }

  @Test
  void login_principalInattendu_leveIllegalStateException() {
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn("pas-un-utilisateur-details");
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    assertThrows(
        IllegalStateException.class,
        () -> authController.login(new LoginRequest("alice", "motdepasse12345"), httpRequest));
  }

  // --- verrouillage par compte ---

  private HttpServletRequest requeteLogin() {
    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));
    return httpRequest;
  }

  private void echouerDixFois(final String identifiant) {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("mauvais mot de passe"));
    for (int i = 0; i < 10; i++) {
      assertThrows(
          BadCredentialsException.class,
          () ->
              authController.login(
                  new LoginRequest(identifiant, "mauvais-mot-de-passe"), requeteLogin()));
    }
  }

  @Test
  void login_dixEchecsSurLeMemeCompte_verrouilleLeCompteEtLeJournalise() {
    echouerDixFois("alice");

    final var erreur =
        assertThrows(
            ErreurMetier.class,
            () ->
                authController.login(new LoginRequest("alice", "motdepasse12345"), requeteLogin()));

    assertEquals(429, erreur.getStatus());
    verify(logAdminService, times(1)).logAdmin(eq("warn"), eq("auth"), contains("alice"), anyMap());
    // La tentative verrouillée n'atteint jamais le gestionnaire d'authentification.
    verify(authenticationManager, times(10)).authenticate(any());
  }

  @Test
  void login_verrouillageInsensibleALaCasse() {
    echouerDixFois("Alice");

    final var erreur =
        assertThrows(
            ErreurMetier.class,
            () ->
                authController.login(new LoginRequest("ALICE", "motdepasse12345"), requeteLogin()));

    assertEquals(429, erreur.getStatus());
  }

  @Test
  void login_autreCompte_nEstPasAffecte() {
    echouerDixFois("alice");
    reset(authenticationManager);
    final UtilisateurDetails principal =
        new UtilisateurDetails("u2", "bob", "bob@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final var reponse =
        authController.login(new LoginRequest("bob", "motdepasse12345"), requeteLogin());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void login_neufEchecsPuisSucces_remetLeCompteurAZero() {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("mauvais mot de passe"));
    for (int i = 0; i < 9; i++) {
      assertThrows(
          BadCredentialsException.class,
          () -> authController.login(new LoginRequest("alice", "faux"), requeteLogin()));
    }
    reset(authenticationManager);
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);
    authController.login(new LoginRequest("alice", "motdepasse12345"), requeteLogin());

    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("mauvais mot de passe"));
    assertThrows(
        BadCredentialsException.class,
        () -> authController.login(new LoginRequest("alice", "faux"), requeteLogin()));
    verify(logAdminService, never()).logAdmin(eq("warn"), eq("auth"), anyString(), anyMap());
  }

  // --- moi ---

  @Test
  void moi_nonAuthentifie_retourne401() {
    final var reponse = authController.moi(null, mock(HttpServletRequest.class));

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  void moi_principalPasUtilisateurDetails_retourne401() {
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn("anonymousUser");

    final var reponse = authController.moi(auth, mock(HttpServletRequest.class));

    assertEquals(HttpStatus.UNAUTHORIZED.value(), reponse.getStatusCode().value());
  }

  @Test
  void moi_authentifieAvecChallengeTotpEnAttente_retourneTotpRequisVrai() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", true, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    final HttpSession session = mock(HttpSession.class);
    when(httpRequest.getSession(false)).thenReturn(session);
    when(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE)).thenReturn(Boolean.TRUE);

    final var reponse = authController.moi(auth, httpRequest);

    assertEquals(200, reponse.getStatusCode().value());
    assertTrue(reponse.getBody().totpRequis());
  }

  @Test
  void moi_authentifieSansSession_retourneTotpRequisFaux() {
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(principal);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getSession(false)).thenReturn(null);

    final var reponse = authController.moi(auth, httpRequest);

    assertFalse(reponse.getBody().totpRequis());
  }
}
