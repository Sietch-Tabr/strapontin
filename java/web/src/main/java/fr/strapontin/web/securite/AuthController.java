/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints d'authentification de l'API REST.
///
/// Gère le login, le logout, la récupération de l'utilisateur courant,
/// et la réinitialisation de mot de passe (forgot-password + reset-password).
///
/// @since 2.0.0
/// @author Kwaite
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private static final SecureRandom rng = new SecureRandom();
  private static final String CLE_EMAIL = "email";
  private static final String CLE_SUCCES = "succes";
  private static final String CLE_ERREUR = "erreur";

  private final AuthenticationManager authenticationManager;
  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;

  public AuthController(
      AuthenticationManager authenticationManager,
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      PasswordEncoder passwordEncoder) {
    this.authenticationManager = authenticationManager;
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
  }

  /// Corps de la requête de login.
  ///
  /// @param identifiant le nom d'utilisateur
  /// @param motDePasse  le mot de passe en clair
  public record LoginRequest(String identifiant, String motDePasse) {}

  /// Corps de la requête de mot de passe oublié.
  ///
  /// @param email l'adresse email de l'utilisateur
  public record ForgotPasswordRequest(String email) {}

  /// Corps de la requête de réinitialisation de mot de passe.
  ///
  /// @param token      le token de réinitialisation
  /// @param motDePasse le nouveau mot de passe en clair
  public record ResetPasswordRequest(String token, String motDePasse) {}

  /// Représentation publique d'un utilisateur authentifié.
  ///
  /// @param id                    l'identifiant unique
  /// @param identifiant           le nom d'utilisateur
  /// @param nom                   le nom d'affichage
  /// @param email                 l'adresse email
  /// @param role                  le rôle (`user` ou `admin`)
  /// @param mustChangeCredentials indique si l'utilisateur doit changer ses credentials
  public record UtilisateurDto(
      String id,
      String identifiant,
      String nom,
      String email,
      String role,
      boolean mustChangeCredentials) {}

  /// Authentifie un utilisateur et crée une session persistante.
  ///
  /// @param req         les credentials (`identifiant` + `motDePasse`)
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

    SecurityContext contexte = SecurityContextHolder.createEmptyContext();
    contexte.setAuthentication(auth);
    SecurityContextHolder.setContext(contexte);

    HttpSession session = httpRequest.getSession(true);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexte);

    if (!(auth.getPrincipal() instanceof UtilisateurDetails details)) {
      throw new IllegalStateException("Principal inattendu après authentification");
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
    if (!(auth != null && auth.isAuthenticated()
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
  /// Retourne toujours 200 pour éviter l'énumération de comptes.
  ///
  /// @param req le corps contenant l'adresse email
  /// @return 200 dans tous les cas
  @PostMapping("/api/auth/forgot-password")
  public ResponseEntity<Map<String, Object>> motDePasseOublie(
      @RequestBody ForgotPasswordRequest req) {
    if (req.email() == null || req.email().isBlank()) {
      return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
    }

    String email = req.email().strip().toLowerCase();

    List<Map<String, Object>> utilisateurs =
        jdbc.queryForList("SELECT id FROM users WHERE email = :email", Map.of(CLE_EMAIL, email));

    if (!utilisateurs.isEmpty()) {
      jdbc.update("DELETE FROM verifications WHERE identifier = :email", Map.of(CLE_EMAIL, email));

      String token = genererToken(32);
      String tokenId = UUID.randomUUID().toString();
      Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

      jdbc.update(
          """
          INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
          VALUES (:id, :identifier, :value, :expiresAt, now(), now())
          """,
          Map.of(
              "id", tokenId,
              "identifier", email,
              "value", token,
              "expiresAt", java.sql.Timestamp.from(expiresAt)));

      String baseUrl =
          configService.lireConfig("PUBLIC_BASE_URL").orElse("https://strapontin.kwaite.fr");
      String lienReset = baseUrl + "/reset-password?token=" + token;
      emailService.envoyerResetMotDePasse(email, lienReset);
    }

    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  /// Réinitialise le mot de passe via un token de vérification.
  ///
  /// @param req le corps contenant le token et le nouveau mot de passe
  /// @return 200 si réinitialisé, 400 si token invalide ou expiré
  @PostMapping("/api/auth/reset-password")
  public ResponseEntity<Map<String, Object>> reinitialiserMotDePasse(
      @RequestBody ResetPasswordRequest req) {
    if (req.token() == null || req.motDePasse() == null) {
      return ResponseEntity.badRequest().body(Map.of(CLE_ERREUR, "Token et mot de passe requis."));
    }
    if (req.motDePasse().length() < 12) {
      return ResponseEntity.badRequest()
          .body(Map.of(CLE_ERREUR, "Le mot de passe doit contenir au moins 12 caractères."));
    }

    List<Map<String, Object>> verifs =
        jdbc.queryForList(
            "SELECT identifier FROM verifications WHERE value = :token AND expires_at > NOW()",
            Map.of("token", req.token()));

    if (verifs.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of(CLE_ERREUR, "Lien invalide ou expiré. Recommencez depuis le formulaire."));
    }

    String email = (String) verifs.get(0).get("identifier");
    String hash = passwordEncoder.encode(req.motDePasse());

    jdbc.update(
        """
        UPDATE accounts SET password = :hash, updated_at = NOW()
        WHERE user_id = (SELECT id FROM users WHERE email = :email)
        """,
        Map.of("hash", hash, CLE_EMAIL, email));

    jdbc.update(
        "UPDATE users SET must_change_credentials = false, updated_at = NOW() WHERE email = :email",
        Map.of(CLE_EMAIL, email));

    jdbc.update("DELETE FROM verifications WHERE value = :token", Map.of("token", req.token()));

    return ResponseEntity.ok(Map.of(CLE_SUCCES, true));
  }

  private String genererToken(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    rng.nextBytes(octets);
    StringBuilder sb = new StringBuilder();
    for (byte b : octets) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
