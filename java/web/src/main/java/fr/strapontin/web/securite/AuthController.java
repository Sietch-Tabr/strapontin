/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints d'authentification de l'API REST.
///
/// <p>HTTP pur — délègue les flux reset MDP à {@link AuthService}.
/// Gère le login, logout, récupération de l'utilisateur courant,
/// et la réinitialisation de mot de passe.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthenticationManager authenticationManager;
  private final AuthService authService;

  /// @param authenticationManager gestionnaire d'authentification Spring Security
  /// @param authService           service de réinitialisation de mot de passe
  public AuthController(AuthenticationManager authenticationManager, AuthService authService) {
    this.authenticationManager = authenticationManager;
    this.authService = authService;
  }

  /// Authentifie un utilisateur et crée une session persistante.
  ///
  /// @param req         les credentials ({@code identifiant} + {@code motDePasse})
  /// @param httpRequest la requête HTTP (pour créer la session)
  /// @return l'utilisateur authentifié ou 401 si les credentials sont invalides
  @PostMapping("/api/auth/login")
  public ResponseEntity<UtilisateurDto> login(
      @RequestBody LoginRequest req, HttpServletRequest httpRequest) {
    Objects.requireNonNull(req.identifiant(), "L'identifiant est requis");
    Objects.requireNonNull(req.motDePasse(), "Le mot de passe est requis");

    Authentication auth =
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                req.identifiant(), req.motDePasse()));

    HttpSession sessionExistante = httpRequest.getSession(false);
    if (sessionExistante != null) sessionExistante.invalidate();

    SecurityContext contexte = SecurityContextHolder.createEmptyContext();
    contexte.setAuthentication(auth);
    SecurityContextHolder.setContext(contexte);

    HttpSession session = httpRequest.getSession(true);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexte);

    if (!(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      throw new IllegalStateException("Principal inattendu après authentification");
    }
    if (details.twoFactorActif()) {
      session.setAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
    }
    log.debug("Connexion réussie : {}", details.identifiant());
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.nom(),
            details.email(),
            details.role(),
            details.doitChangerCredentials()));
  }

  /// Invalide la session courante et déconnecte l'utilisateur.
  ///
  /// @param httpRequest la requête HTTP (pour invalider la session)
  /// @return 204 No Content
  @PostMapping("/api/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
    HttpSession session = httpRequest.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  /// Retourne l'utilisateur actuellement authentifié.
  ///
  /// @param auth l'authentification Spring Security (injectée automatiquement)
  /// @return l'utilisateur courant ou 401 si non authentifié
  @GetMapping("/api/auth/me")
  public ResponseEntity<UtilisateurDto> moi(Authentication auth) {
    if (!(auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails details)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.nom(),
            details.email(),
            details.role(),
            details.doitChangerCredentials()));
  }

  /// Déclenche l'envoi d'un email de réinitialisation de mot de passe.
  ///
  /// <p>Retourne toujours 200 pour éviter l'énumération de comptes.
  ///
  /// @param req        le corps contenant l'adresse email
  /// @param httpRequest la requête HTTP (pour construire l'URL de reset)
  /// @return 200 dans tous les cas
  @PostMapping("/api/auth/forgot-password")
  public ResponseEntity<Map<String, Object>> motDePasseOublie(
      @RequestBody ForgotPasswordRequest req, HttpServletRequest httpRequest) {
    if (req.email() != null && !req.email().isBlank()) {
      authService.motDePasseOublie(
          req.email().strip().toLowerCase(), baseUrlDepuisRequete(httpRequest));
    }
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Réinitialise le mot de passe via un token de vérification.
  ///
  /// @param req le corps contenant le token et le nouveau mot de passe
  /// @return 200 si réinitialisé — 400 si invalide (via {@link GlobalExceptionHandler})
  @PostMapping("/api/auth/reset-password")
  public ResponseEntity<Map<String, Object>> reinitialiserMotDePasse(
      @RequestBody ResetPasswordRequest req) {
    if (req.token() == null || req.motDePasse() == null) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Token et mot de passe requis."));
    }
    if (req.motDePasse().length() < 12) {
      return ResponseEntity.badRequest()
          .body(Map.of("erreur", "Le mot de passe doit contenir au moins 12 caractères."));
    }
    authService.reinitialiserMotDePasse(req.token(), req.motDePasse());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  private static String baseUrlDepuisRequete(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
      return scheme + "://" + host + request.getContextPath();
    }
    return scheme + "://" + host + ":" + port + request.getContextPath();
  }
}
