/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/// Contrôleur MVC Thymeleaf pour les pages d'authentification.
///
/// Gère les vues `/login`, `/forgot-password` et `/reset-password`.
/// Le traitement du formulaire `POST /login` est délégué à Spring Security.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AuthViewController {

  private static final SecureRandom rng = new SecureRandom();
  private static final String VUE_FORGOT_PASSWORD = "auth/forgot-password";
  private static final String VUE_RESET_PASSWORD = "auth/reset-password";
  private static final String VUE_TOTP = "auth/totp";
  private static final String CLE_EMAIL = "email";
  private static final String CLE_ERREUR = "erreur";
  private static final String CLE_TOKEN = "token";
  private static final String SESSION_ECHECS_TOTP = "TOTP_ECHECS";
  private static final int MAX_ECHECS_TOTP = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;
  private final TotpService totpService;

  public AuthViewController(
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      PasswordEncoder passwordEncoder,
      TotpService totpService) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
    this.totpService = totpService;
  }

  /// Affiche la page de connexion.
  ///
  /// @return le nom du template Thymeleaf
  @GetMapping("/login")
  public String login() {
    return "auth/login";
  }

  /// Affiche la page de challenge TOTP post-login.
  ///
  /// @param session la session HTTP courante
  /// @return le template TOTP, ou redirect vers {@code /} si aucun challenge en attente
  @GetMapping("/auth/totp")
  public String afficherChallengeTotp(HttpSession session) {
    if (!Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE))) {
      return "redirect:/";
    }
    return VUE_TOTP;
  }

  /// Vérifie le code TOTP soumis par l'utilisateur.
  ///
  /// <p>Après 5 échecs consécutifs, la session est invalidée et l'utilisateur
  /// est renvoyé vers {@code /login}.
  ///
  /// @param code    le code à 6 chiffres saisi
  /// @param modele  le modèle Thymeleaf
  /// @param session la session HTTP courante
  /// @param auth    le contexte d'authentification Spring Security
  /// @return redirect vers {@code /} si succès, sinon ré-affichage avec erreur
  @PostMapping("/auth/totp")
  public String verifierChallengeTotp(
      @RequestParam(required = false, defaultValue = "") String code,
      Model modele,
      HttpSession session,
      Authentication auth) {
    if (!Boolean.TRUE.equals(session.getAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE))) {
      return "redirect:/";
    }

    UtilisateurDetails principal = (UtilisateurDetails) auth.getPrincipal();
    List<String> secrets =
        jdbc.queryForList(
            "SELECT two_factor_secret FROM users WHERE id = :id",
            Map.of("id", principal.id()),
            String.class);

    String secret = secrets.isEmpty() ? null : secrets.get(0);
    if (totpService.verifierCode(secret, code)) {
      session.removeAttribute(TotpSuccessHandler.SESSION_TOTP_EN_ATTENTE);
      session.removeAttribute(SESSION_ECHECS_TOTP);
      return "redirect:/";
    }

    int echecs =
        ((Integer) session.getAttribute(SESSION_ECHECS_TOTP) != null)
            ? (Integer) session.getAttribute(SESSION_ECHECS_TOTP) + 1
            : 1;

    if (echecs >= MAX_ECHECS_TOTP) {
      session.invalidate();
      return "redirect:/login?erreur=true";
    }

    session.setAttribute(SESSION_ECHECS_TOTP, echecs);
    modele.addAttribute(
        CLE_ERREUR, "Code incorrect. " + (MAX_ECHECS_TOTP - echecs) + " essai(s) restant(s).");
    return VUE_TOTP;
  }

  /// Affiche le formulaire de mot de passe oublié.
  ///
  /// @return le nom du template Thymeleaf
  @GetMapping("/forgot-password")
  public String formulaireMotDePasseOublie() {
    return VUE_FORGOT_PASSWORD;
  }

  /// Traite la soumission du formulaire de mot de passe oublié.
  ///
  /// Retourne toujours la même vue avec `succes=true` pour éviter l'énumération.
  ///
  /// @param email  l'adresse email soumise
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template Thymeleaf
  @PostMapping("/forgot-password")
  public String traiterMotDePasseOublie(
      @RequestParam(required = false) String email, Model modele) {
    if (email != null && !email.isBlank()) {
      String emailNorm = email.strip().toLowerCase();
      List<Map<String, Object>> utilisateurs =
          jdbc.queryForList(
              "SELECT id FROM users WHERE email = :email", Map.of(CLE_EMAIL, emailNorm));

      if (!utilisateurs.isEmpty()) {
        String prefixe = "reset:" + emailNorm;
        jdbc.update(
            "DELETE FROM verifications WHERE identifier = :prefixe",
            Map.of("prefixe", prefixe));

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
                "identifier", prefixe,
                "value", sha256Hex(token),
                "expiresAt", java.sql.Timestamp.from(expiresAt)));

        String baseUrl =
            configService.lireConfig("PUBLIC_BASE_URL").orElse("https://strapontin.kwaite.fr");
        emailService.envoyerResetMotDePasse(emailNorm, baseUrl + "/reset-password?token=" + token);
      }
    }

    modele.addAttribute("succes", true);
    return VUE_FORGOT_PASSWORD;
  }

  /// Affiche le formulaire de réinitialisation de mot de passe.
  ///
  /// @param token  le token de réinitialisation (depuis l'URL)
  /// @param modele le modèle Thymeleaf
  /// @return le nom du template Thymeleaf ou redirect si token absent
  @GetMapping("/reset-password")
  public String formulaireResetPassword(
      @RequestParam(required = false) String token, Model modele) {
    if (token == null || token.isBlank()) {
      return "redirect:/forgot-password";
    }
    List<Map<String, Object>> verifs =
        jdbc.queryForList(
            "SELECT id FROM verifications WHERE value = :hash AND expires_at > NOW()",
            Map.of("hash", sha256Hex(token)));
    if (verifs.isEmpty()) {
      modele.addAttribute(CLE_ERREUR, "Lien invalide ou expiré. Recommencez depuis le formulaire.");
      return VUE_FORGOT_PASSWORD;
    }
    modele.addAttribute(CLE_TOKEN, token);
    return VUE_RESET_PASSWORD;
  }

  /// Traite la soumission du formulaire de réinitialisation de mot de passe.
  ///
  /// @param token      le token de réinitialisation
  /// @param motDePasse le nouveau mot de passe
  /// @param modele     le modèle Thymeleaf
  /// @param request    la requête HTTP (pour invalider la session serveur)
  /// @param response   la réponse HTTP (pour supprimer le cookie SESSION)
  /// @return redirect vers /login en cas de succès, sinon la vue avec erreur
  @PostMapping("/reset-password")
  public String traiterResetPassword(
      @RequestParam String token,
      @RequestParam String motDePasse,
      Model modele,
      HttpServletRequest request,
      HttpServletResponse response) {

    if (motDePasse.length() < 12) {
      modele.addAttribute(CLE_ERREUR, "Le mot de passe doit contenir au moins 12 caractères.");
      modele.addAttribute(CLE_TOKEN, token);
      return VUE_RESET_PASSWORD;
    }

    String tokenHash = sha256Hex(token);
    List<Map<String, Object>> verifs =
        jdbc.queryForList(
            "SELECT identifier FROM verifications WHERE value = :hash AND expires_at > NOW()",
            Map.of("hash", tokenHash));

    if (verifs.isEmpty()) {
      modele.addAttribute(CLE_ERREUR, "Lien invalide ou expiré. Recommencez depuis le formulaire.");
      return VUE_FORGOT_PASSWORD;
    }

    String identifiant = (String) verifs.get(0).get("identifier");
    String email = identifiant.contains(":") ? identifiant.split(":", 2)[1] : identifiant;
    String hash = passwordEncoder.encode(motDePasse);

    jdbc.update(
        """
        UPDATE accounts SET password = :hash, updated_at = NOW()
        WHERE user_id = (SELECT id FROM users WHERE email = :email)
        """,
        Map.of("hash", hash, CLE_EMAIL, email));

    jdbc.update(
        "UPDATE users SET must_change_credentials = false, updated_at = NOW() WHERE email = :email",
        Map.of(CLE_EMAIL, email));

    jdbc.update("DELETE FROM verifications WHERE value = :hash", Map.of("hash", tokenHash));

    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();

    Cookie sessionCookie = new Cookie("SESSION", "");
    sessionCookie.setMaxAge(0);
    sessionCookie.setPath("/");
    sessionCookie.setSecure(true);
    response.addCookie(sessionCookie);

    return "redirect:/login?reset=true";
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

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 non disponible", e);
    }
  }
}
