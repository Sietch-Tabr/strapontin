/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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

import java.util.Objects;

/// Endpoints d'authentification de l'API REST.
///
/// <p>Gère le login, le logout et la récupération de l'utilisateur courant.
/// Le login accepte un corps JSON {@code {identifiant, motDePasse}} et retourne
/// une session HTTP (cookie {@code SESSION}).
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthenticationManager authenticationManager;

  public AuthController(AuthenticationManager authenticationManager) {
    this.authenticationManager = authenticationManager;
  }

  /// Corps de la requête de login.
  ///
  /// @param identifiant le nom d'utilisateur
  /// @param motDePasse  le mot de passe en clair
  public record LoginRequest(String identifiant, String motDePasse) {}

  /// Représentation publique d'un utilisateur authentifié.
  ///
  /// @param id          l'identifiant unique
  /// @param identifiant le nom d'utilisateur
  /// @param role        le rôle ({@code user} ou {@code admin})
  public record UtilisateurDto(String id, String identifiant, String role) {}

  /// Authentifie un utilisateur et crée une session persistante.
  ///
  /// @param req         les credentials ({@code identifiant} + {@code motDePasse})
  /// @param httpRequest la requête HTTP (pour créer la session)
  /// @return l'utilisateur authentifié ou 401 si les credentials sont invalides
  @PostMapping("/api/auth/login")
  public ResponseEntity<UtilisateurDto> login(
      @RequestBody LoginRequest req,
      HttpServletRequest httpRequest
  ) {
    Objects.requireNonNull(req.identifiant(), "L'identifiant est requis");
    Objects.requireNonNull(req.motDePasse(), "Le mot de passe est requis");

    Authentication auth = authenticationManager.authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(req.identifiant(), req.motDePasse())
    );

    SecurityContext contexte = SecurityContextHolder.createEmptyContext();
    contexte.setAuthentication(auth);
    SecurityContextHolder.setContext(contexte);

    HttpSession session = httpRequest.getSession(true);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        contexte
    );

    UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    log.debug("Connexion réussie : {}", details.identifiant());
    return ResponseEntity.ok(new UtilisateurDto(details.id(), details.identifiant(), details.role()));
  }

  /// Invalide la session courante et déconnecte l'utilisateur.
  ///
  /// @param httpRequest la requête HTTP (pour invalider la session)
  /// @return 204 No Content
  @PostMapping("/api/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
    HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  /// Retourne l'utilisateur actuellement authentifié.
  ///
  /// @param auth l'authentification Spring Security (injectée automatiquement)
  /// @return l'utilisateur courant ou 401 si non authentifié
  @GetMapping("/api/auth/me")
  public ResponseEntity<UtilisateurDto> moi(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    UtilisateurDetails details = (UtilisateurDetails) auth.getPrincipal();
    return ResponseEntity.ok(new UtilisateurDto(details.id(), details.identifiant(), details.role()));
  }
}
