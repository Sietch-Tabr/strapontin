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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Tests unitaires pour {@link PasswordResetController} — réinitialisation de mot de passe,
 * activation d'invitation, résolution du type de token.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class PasswordResetControllerTest {

  private PasswordResetController controller;
  private AuthenticationManager authenticationManager;
  private AuthService authService;
  private ConfigService configService;
  private LogAdminService logAdminService;

  @BeforeEach
  void setUp() {
    authenticationManager = mock(AuthenticationManager.class);
    authService = mock(AuthService.class);
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    controller =
        new PasswordResetController(
            authenticationManager, authService, configService, logAdminService);
  }

  // --- reinitialiserMotDePasse ---
  // token/motDePasse manquants ou trop courts : voir ResetPasswordRequestValidationTest
  // (@Valid ne s'exerce pas sur un appel Java direct au contrôleur, seulement via Spring MVC).

  @Test
  void reinitialiserMotDePasse_identifiantTropCourt_retourne400() {
    final var reponse =
        controller.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "motdepasse12345", "ab"),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantTropLong_retourne400() {
    final var reponse =
        controller.reinitialiserMotDePasse(
            new ResetPasswordRequest("token", "motdepasse12345", "a".repeat(31)),
            mock(HttpServletRequest.class));
    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void reinitialiserMotDePasse_identifiantFormatInvalide_retourne400() {
    final var reponse =
        controller.reinitialiserMotDePasse(
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
            controller.reinitialiserMotDePasse(
                new ResetPasswordRequest("token", "motdepasse12345", "alice.dupont-2"),
                mock(HttpServletRequest.class)),
        "alice.dupont-2 doit passer la validation de format et atteindre activerCompteInvite");
  }

  @Test
  void reinitialiserMotDePasse_activationInvitation_peupleWebAuthenticationDetailsSurLeToken() {
    when(authService.activerCompteInvite("token", "alice", "motdepasse12345")).thenReturn("alice");
    final UtilisateurDetails principal =
        new UtilisateurDetails("u1", "alice", "alice@test.fr", "hash", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    when(authenticationManager.authenticate(any())).thenReturn(auth);

    final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
    when(httpRequest.getSession(false)).thenReturn(null);
    when(httpRequest.getSession(true)).thenReturn(mock(HttpSession.class));

    controller.reinitialiserMotDePasse(
        new ResetPasswordRequest("token", "motdepasse12345", "alice"), httpRequest);

    final ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
        ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
    verify(authenticationManager).authenticate(tokenCaptor.capture());
    assertNotNull(tokenCaptor.getValue().getDetails());
    assertInstanceOf(WebAuthenticationDetails.class, tokenCaptor.getValue().getDetails());
  }

  @Test
  void reinitialiserMotDePasse_resetClassique_retourne200() {
    doNothing().when(authService).reinitialiserMotDePasse(anyString(), anyString());

    final var reponse =
        controller.reinitialiserMotDePasse(
            new ResetPasswordRequest("tokenvalide", "motdepasse12345", null),
            mock(HttpServletRequest.class));

    assertEquals(200, reponse.getStatusCode().value());
  }

  // --- motDePasseOublie (endpoint) ---

  @Test
  void motDePasseOublieEndpoint_emailBlanc_neDelegueRienEtRetourne200() {
    final var reponse = controller.motDePasseOublie(new ForgotPasswordRequest(""));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService, never()).motDePasseOublie(anyString(), anyString());
  }

  @Test
  void motDePasseOublieEndpoint_emailPresent_normaliseEtDelegueAuService() {
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));

    final var reponse = controller.motDePasseOublie(new ForgotPasswordRequest(" Alice@Test.FR "));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService).motDePasseOublie("alice@test.fr", "https://strapontin.fr");
  }

  // PUBLIC_BASE_URL absente : ne doit jamais retomber sur le header Host de la requête entrante
  // (Host Header Injection) — le lien de réinitialisation ne doit tout simplement pas être
  // envoyé, avec une alerte logs_admin plutôt qu'un lien potentiellement redirigé vers un domaine
  // contrôlé par un attaquant.
  @Test
  void motDePasseOublieEndpoint_baseUrlNonConfiguree_neDelegueRienEtAlerte() {
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.empty());

    final var reponse = controller.motDePasseOublie(new ForgotPasswordRequest("alice@test.fr"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(authService, never()).motDePasseOublie(anyString(), anyString());
    verify(logAdminService).logAdmin(eq("error"), eq("auth"), anyString());
  }

  // --- typeToken ---

  @Test
  void typeToken_tokenInvitation_retourneTypeInvite() {
    when(authService.estTokenInvite("token")).thenReturn(true);

    final var reponse = controller.typeToken("token");

    assertEquals("invite", reponse.getBody().get("type"));
  }

  @Test
  void typeToken_tokenResetOuInconnu_retourneTypeReset() {
    when(authService.estTokenInvite("token")).thenReturn(false);

    final var reponse = controller.typeToken("token");

    assertEquals("reset", reponse.getBody().get("type"));
  }
}
