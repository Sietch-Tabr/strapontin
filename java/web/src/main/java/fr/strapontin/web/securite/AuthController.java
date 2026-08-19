/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints d'authentification de l'API REST.
///
/// <p>HTTP pur — délègue les flux reset MDP à {@link AuthService} et la vérification TOTP (code +
/// compteur d'échecs + verrouillage) à {@link TotpChallengeService}. Gère le login, logout,
/// challenge TOTP, récupération de l'utilisateur courant et la réinitialisation de mot de passe.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private static final Pattern FORMAT_IDENTIFIANT = Pattern.compile("^[a-zA-Z0-9._-]+$");

  private final AuthenticationManager authenticationManager;
  private final AuthService authService;
  private final TotpChallengeService totpChallengeService;
  private final ConfigService configService;
  private final LogAdminService logAdminService;

  /// @param authenticationManager gestionnaire d'authentification Spring Security
  /// @param authService service de réinitialisation de mot de passe
  /// @param totpChallengeService vérification du challenge TOTP (code + compteur d'échecs)
  /// @param configService service de configuration pour lire PUBLIC_BASE_URL
  /// @param logAdminService audit des tentatives bloquées faute de PUBLIC_BASE_URL configurée
  public AuthController(
      AuthenticationManager authenticationManager,
      AuthService authService,
      TotpChallengeService totpChallengeService,
      ConfigService configService,
      LogAdminService logAdminService) {
    this.authenticationManager = authenticationManager;
    this.authService = authService;
    this.totpChallengeService = totpChallengeService;
    this.configService = configService;
    this.logAdminService = logAdminService;
  }

  /// Authentifie un utilisateur et crée une session persistante.
  ///
  /// @param req les credentials ({@code identifiant} + {@code motDePasse})
  /// @param httpRequest la requête HTTP (pour créer la session)
  /// @return l'utilisateur authentifié ou 401 si les credentials sont invalides
  @PostMapping("/api/auth/login")
  public ResponseEntity<UtilisateurDto> login(
      @RequestBody LoginRequest req, HttpServletRequest httpRequest) {
    Objects.requireNonNull(req.identifiant(), "L'identifiant est requis");
    Objects.requireNonNull(req.motDePasse(), "Le mot de passe est requis");

    UsernamePasswordAuthenticationToken tokenNonAuthentifie =
        UsernamePasswordAuthenticationToken.unauthenticated(req.identifiant(), req.motDePasse());
    tokenNonAuthentifie.setDetails(new WebAuthenticationDetails(httpRequest));
    Authentication auth = authenticationManager.authenticate(tokenNonAuthentifie);

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
    boolean totpRequis = details.twoFactorActif();
    if (totpRequis) {
      session.setAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE, Boolean.TRUE);
    }
    log.debug("Connexion réussie : {}", details.identifiant());
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.email(),
            details.role(),
            details.doitChangerCredentials(),
            totpRequis));
  }

  /// Invalide la session courante, efface le cookie SESSION et déconnecte l'utilisateur.
  ///
  /// @param httpRequest la requête HTTP (pour invalider la session)
  /// @param httpResponse la réponse HTTP (pour supprimer le cookie SESSION)
  /// @return 204 No Content
  @PostMapping("/api/auth/logout")
  public ResponseEntity<Void> logout(
      HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    HttpSession session = httpRequest.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    Cookie cookie = new Cookie("SESSION", "");
    cookie.setMaxAge(0);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setAttribute("SameSite", "Lax");
    httpResponse.addCookie(cookie);
    return ResponseEntity.noContent().build();
  }

  /// Vérifie le code TOTP soumis pendant le challenge post-login.
  ///
  /// <p>Le compteur d'échecs et la logique de verrouillage sont délégués à {@link
  /// TotpChallengeService}. Un admin peut débloquer via {@code POST
  /// /api/admin/utilisateurs/{id}/reset-totp}.
  ///
  /// @param req le corps contenant le code à 6 chiffres
  /// @param httpRequest la requête HTTP (pour lire et modifier la session)
  /// @param auth le contexte d'authentification Spring Security
  /// @return 200 si valide — 422 si code incorrect — 409 si pas de challenge en attente — 401 si
  ///   trop d'échecs ou compte verrouillé
  @PostMapping("/api/auth/totp")
  public ResponseEntity<Map<String, Object>> verifierTotp(
      @RequestBody TotpChallengeRequest req, HttpServletRequest httpRequest, Authentication auth) {
    HttpSession session = httpRequest.getSession(false);
    if (session == null
        || !Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE))) {
      return ResponseEntity.status(409).body(Map.of("erreur", "Aucun challenge TOTP en attente"));
    }

    if (!(auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails principal)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("erreur", "Authentification requise"));
    }

    ResultatTotp resultat =
        totpChallengeService.verifier(
            principal.id(), principal.identifiant(), req.code(), httpRequest.getRemoteAddr());

    return switch (resultat.statut()) {
      case DEJA_VERROUILLE -> {
        session.invalidate();
        log.debug("Compte TOTP verrouillé, session invalidée : {}", principal.identifiant());
        yield ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("erreur", "Compte verrouillé. Contactez un administrateur."));
      }
      case SUCCES -> {
        session.removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
        log.debug("Challenge TOTP validé : {}", principal.identifiant());
        yield ResponseEntity.ok(Map.of("succes", true));
      }
      case VERROUILLE_MAINTENANT -> {
        session.invalidate();
        log.debug("Trop de tentatives TOTP, session invalidée : {}", principal.identifiant());
        yield ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("erreur", "Trop de tentatives. Compte verrouillé."));
      }
      case ECHEC ->
          ResponseEntity.status(422)
              .body(
                  Map.of(
                      "erreur",
                      "Code incorrect. " + resultat.essaisRestants() + " essai(s) restant(s)."));
    };
  }

  /// Retourne l'utilisateur actuellement authentifié.
  ///
  /// <p>Le champ {@code totpRequis} est {@code true} si un challenge TOTP est encore en attente
  /// pour la session courante (l'utilisateur s'est connecté mais n'a pas encore validé le 2FA).
  ///
  /// @param auth l'authentification Spring Security (injectée automatiquement)
  /// @param httpRequest la requête HTTP (pour lire l'état TOTP de la session)
  /// @return l'utilisateur courant ou 401 si non authentifié
  @GetMapping("/api/auth/me")
  public ResponseEntity<UtilisateurDto> moi(Authentication auth, HttpServletRequest httpRequest) {
    if (!(auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof UtilisateurDetails details)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    HttpSession session = httpRequest.getSession(false);
    boolean totpRequis =
        session != null
            && Boolean.TRUE.equals(
                session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE));
    return ResponseEntity.ok(
        new UtilisateurDto(
            details.id(),
            details.identifiant(),
            details.email(),
            details.role(),
            details.doitChangerCredentials(),
            totpRequis));
  }

  /// Déclenche l'envoi d'un email de réinitialisation de mot de passe.
  ///
  /// <p>Retourne toujours 200 pour éviter l'énumération de comptes.
  ///
  /// @param req le corps contenant l'adresse email
  /// @param httpRequest la requête HTTP (pour construire l'URL de reset)
  /// @return 200 dans tous les cas
  @PostMapping("/api/auth/forgot-password")
  public ResponseEntity<Map<String, Object>> motDePasseOublie(
      @RequestBody ForgotPasswordRequest req) {
    if (req.email() != null && !req.email().isBlank()) {
      resoudreBaseUrl()
          .ifPresentOrElse(
              baseUrl -> authService.motDePasseOublie(req.email().strip().toLowerCase(), baseUrl),
              () ->
                  logAdminService.logAdmin(
                      "error",
                      "auth",
                      "Réinitialisation de mot de passe bloquée — PUBLIC_BASE_URL non configurée"));
    }
    // Réponse identique que l'email existe ou non, et que PUBLIC_BASE_URL soit configurée ou
    // non — évite toute énumération de compte ou fuite de configuration au client.
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Retourne le type d'un token de vérification sans le consommer.
  ///
  /// <p>Retourne toujours 200 — {@code "reset"} si le token est absent ou invalide (opacité).
  ///
  /// @param token token brut (non hashé)
  /// @return {@code {type: "invite"}} ou {@code {type: "reset"}}
  @GetMapping("/api/auth/type-token")
  public ResponseEntity<Map<String, Object>> typeToken(@RequestParam String token) {
    boolean estInvite = authService.estTokenInvite(token);
    return ResponseEntity.ok(Map.of("type", estInvite ? "invite" : "reset"));
  }

  /// Réinitialise le mot de passe via un token de vérification.
  ///
  /// <p>Si le token est de type {@code invite:} et que {@code identifiant} est fourni, active le
  /// compte en une seule opération (identifiant + mot de passe) et ouvre une session.
  ///
  /// @param req le corps contenant le token, le mot de passe et optionnellement l'identifiant
  /// @param httpRequest pour créer la session lors de l'activation d'une invitation
  /// @return 200 si réinitialisé — 400 si invalide
  @PostMapping("/api/auth/reset-password")
  public ResponseEntity<?> reinitialiserMotDePasse(
      @RequestBody ResetPasswordRequest req, HttpServletRequest httpRequest) {
    if (req.token() == null || req.motDePasse() == null) {
      return ResponseEntity.badRequest().body(Map.of("erreur", "Token et mot de passe requis."));
    }
    if (req.motDePasse().length() < 12) {
      return ResponseEntity.badRequest()
          .body(Map.of("erreur", "Le mot de passe doit contenir au moins 12 caractères."));
    }

    if (req.identifiant() != null && !req.identifiant().isBlank()) {
      String ident = req.identifiant().strip().toLowerCase();
      if (ident.length() < 3 || ident.length() > 30) {
        return ResponseEntity.badRequest()
            .body(Map.of("erreur", "L'identifiant doit contenir entre 3 et 30 caractères."));
      }
      if (!FORMAT_IDENTIFIANT.matcher(ident).matches()) {
        return ResponseEntity.badRequest()
            .body(
                Map.of(
                    "erreur",
                    "L'identifiant ne peut contenir que des lettres, chiffres, points,"
                        + " tirets et underscores."));
      }
      String identFinal = authService.activerCompteInvite(req.token(), ident, req.motDePasse());

      UsernamePasswordAuthenticationToken tokenNonAuthentifie =
          UsernamePasswordAuthenticationToken.unauthenticated(identFinal, req.motDePasse());
      tokenNonAuthentifie.setDetails(new WebAuthenticationDetails(httpRequest));
      Authentication auth = authenticationManager.authenticate(tokenNonAuthentifie);

      HttpSession sessionExistante = httpRequest.getSession(false);
      if (sessionExistante != null) sessionExistante.invalidate();

      SecurityContext contexte = SecurityContextHolder.createEmptyContext();
      contexte.setAuthentication(auth);
      SecurityContextHolder.setContext(contexte);

      HttpSession session = httpRequest.getSession(true);
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexte);

      if (!(auth.getPrincipal() instanceof UtilisateurDetails details)) {
        throw new IllegalStateException("Principal inattendu après activation invitation");
      }
      log.debug("Activation compte invitation, connexion : {}", details.identifiant());
      return ResponseEntity.ok(
          new UtilisateurDto(
              details.id(), details.identifiant(), details.email(), details.role(), false, false));
    }

    authService.reinitialiserMotDePasse(req.token(), req.motDePasse());
    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Résout l'URL de base publique pour construire un lien envoyé par email (reset de mot de
  /// passe).
  ///
  /// <p>Ne retombe jamais sur le header {@code Host}/{@code getServerName()} de la requête entrante
  /// : un attaquant contrôlant ce header pourrait rediriger le lien de réinitialisation vers un
  /// domaine sous son contrôle et voler le token dès qu'un utilisateur clique dessus (Host Header
  /// Injection). {@link Optional#empty()} plutôt qu'un repli non sûr si {@code PUBLIC_BASE_URL}
  /// n'est pas configurée — l'appelant doit alors refuser d'envoyer le lien.
  ///
  /// @return l'URL de base configurée, ou vide si absente
  private Optional<String> resoudreBaseUrl() {
    return configService.lireConfig("PUBLIC_BASE_URL").filter(s -> !s.isBlank());
  }
}
