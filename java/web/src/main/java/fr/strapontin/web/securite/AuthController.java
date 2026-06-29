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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints d'authentification de l'API REST.
///
/// <p>HTTP pur — délègue les flux reset MDP à {@link AuthService} et la vérification TOTP
/// à {@link TotpService}. Gère le login, logout, challenge TOTP, récupération de l'utilisateur
/// courant et la réinitialisation de mot de passe.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private static final String SESSION_ECHECS_TOTP = "TOTP_ECHECS";
  private static final int MAX_ECHECS_TOTP = 5;
  private static final Pattern FORMAT_IDENTIFIANT = Pattern.compile("^[a-zA-Z0-9._-]+$");

  private final AuthenticationManager authenticationManager;
  private final AuthService authService;
  private final TotpService totpService;
  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final LogAdminService logAdminService;

  /// @param authenticationManager gestionnaire d'authentification Spring Security
  /// @param authService           service de réinitialisation de mot de passe
  /// @param totpService           service de vérification des codes TOTP
  /// @param jdbc                  accès JDBC pour récupérer le secret TOTP de l'utilisateur
  /// @param configService         service de configuration pour lire PUBLIC_BASE_URL
  /// @param logAdminService       journalisation des échecs TOTP dans logs_admin
  public AuthController(
      AuthenticationManager authenticationManager,
      AuthService authService,
      TotpService totpService,
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      LogAdminService logAdminService) {
    this.authenticationManager = authenticationManager;
    this.authService = authService;
    this.totpService = totpService;
    this.jdbc = jdbc;
    this.configService = configService;
    this.logAdminService = logAdminService;
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
  /// @param httpRequest  la requête HTTP (pour invalider la session)
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
  /// <p>Après {@value MAX_ECHECS_TOTP} échecs consécutifs, la session est invalidée.
  ///
  /// @param req         le corps contenant le code à 6 chiffres
  /// @param httpRequest la requête HTTP (pour lire et modifier la session)
  /// @param auth        le contexte d'authentification Spring Security
  /// @return 200 si valide — 422 si code incorrect — 409 si pas de challenge en attente — 401 si
  ///     trop d'échecs
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

    List<String> secrets =
        jdbc.queryForList(
            "SELECT two_factor_secret FROM users WHERE id = :id",
            Map.of("id", principal.id()),
            String.class);

    String secret = secrets.isEmpty() ? null : secrets.get(0);
    if (totpService.verifierCode(secret, req.code())) {
      session.removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
      session.removeAttribute(SESSION_ECHECS_TOTP);
      log.debug("Challenge TOTP validé : {}", principal.identifiant());
      logAdminService.logAdmin(
          "info",
          "auth",
          "TOTP validé : " + principal.identifiant(),
          Map.of("identifiant", principal.identifiant(), "ip", httpRequest.getRemoteAddr()));
      return ResponseEntity.ok(Map.of("succes", true));
    }

    int echecs = (session.getAttribute(SESSION_ECHECS_TOTP) instanceof Integer n) ? n + 1 : 1;
    logAdminService.logAdmin(
        "warn",
        "auth",
        "Échec TOTP : " + principal.identifiant(),
        Map.of(
            "identifiant", principal.identifiant(),
            "ip", httpRequest.getRemoteAddr(),
            "essais", echecs));

    if (echecs >= MAX_ECHECS_TOTP) {
      session.invalidate();
      log.debug("Trop de tentatives TOTP, session invalidée : {}", principal.identifiant());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("erreur", "Trop de tentatives"));
    }

    session.setAttribute(SESSION_ECHECS_TOTP, echecs);
    int restants = MAX_ECHECS_TOTP - echecs;
    return ResponseEntity.status(422)
        .body(Map.of("erreur", "Code incorrect. " + restants + " essai(s) restant(s)."));
  }

  /// Retourne l'utilisateur actuellement authentifié.
  ///
  /// <p>Le champ {@code totpRequis} est {@code true} si un challenge TOTP est encore en attente
  /// pour la session courante (l'utilisateur s'est connecté mais n'a pas encore validé le 2FA).
  ///
  /// @param auth        l'authentification Spring Security (injectée automatiquement)
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
  /// <p>Si le token est de type {@code invite:} et que {@code identifiant} est fourni,
  /// active le compte en une seule opération (identifiant + mot de passe) et ouvre une session.
  ///
  /// @param req        le corps contenant le token, le mot de passe et optionnellement
  // l'identifiant
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

      Authentication auth =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(identFinal, req.motDePasse()));

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

  private String baseUrlDepuisRequete(HttpServletRequest request) {
    return configService
        .lireConfig("PUBLIC_BASE_URL")
        .filter(s -> !s.isBlank())
        .orElseGet(
            () -> {
              String scheme = request.getScheme();
              String host = request.getServerName();
              int port = request.getServerPort();
              if (("http".equals(scheme) && port == 80)
                  || ("https".equals(scheme) && port == 443)) {
                return scheme + "://" + host + request.getContextPath();
              }
              return scheme + "://" + host + ":" + port + request.getContextPath();
            });
  }
}
