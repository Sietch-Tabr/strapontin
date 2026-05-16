/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.securite;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/// Contrôleur MVC Thymeleaf pour les pages d'authentification.
///
/// <p>Gère les vues {@code /login}, {@code /forgot-password} et {@code /reset-password}.
/// Le traitement du formulaire {@code POST /login} est délégué à Spring Security.
///
/// @since 2.0.0
/// @author Kwaite
@Controller
public class AuthViewController {

  private static final SecureRandom rng = new SecureRandom();

  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigService configService;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;

  public AuthViewController(
      NamedParameterJdbcTemplate jdbc,
      ConfigService configService,
      EmailService emailService,
      PasswordEncoder passwordEncoder) {
    this.jdbc = jdbc;
    this.configService = configService;
    this.emailService = emailService;
    this.passwordEncoder = passwordEncoder;
  }

  /// Affiche la page de connexion.
  ///
  /// @return le nom du template Thymeleaf
  @GetMapping("/login")
  public String login() {
    return "auth/login";
  }

  /// Affiche le formulaire de mot de passe oublié.
  ///
  /// @return le nom du template Thymeleaf
  @GetMapping("/forgot-password")
  public String formulaireMotDePasseOublie() {
    return "auth/forgot-password";
  }

  /// Traite la soumission du formulaire de mot de passe oublié.
  ///
  /// <p>Retourne toujours la même vue avec {@code succes=true} pour éviter l'énumération.
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
              "SELECT id FROM users WHERE email = :email", Map.of("email", emailNorm));

      if (!utilisateurs.isEmpty()) {
        jdbc.update(
            "DELETE FROM verifications WHERE identifier = :email", Map.of("email", emailNorm));

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
                "identifier", emailNorm,
                "value", token,
                "expiresAt", java.sql.Timestamp.from(expiresAt)));

        String baseUrl =
            configService.lireConfig("PUBLIC_BASE_URL").orElse("https://strapontin.kwaite.fr");
        emailService.envoyerResetMotDePasse(emailNorm, baseUrl + "/reset-password?token=" + token);
      }
    }

    modele.addAttribute("succes", true);
    return "auth/forgot-password";
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
            "SELECT id FROM verifications WHERE value = :token AND expires_at > NOW()",
            Map.of("token", token));
    if (verifs.isEmpty()) {
      modele.addAttribute("erreur", "Lien invalide ou expiré. Recommencez depuis le formulaire.");
      return "auth/forgot-password";
    }
    modele.addAttribute("token", token);
    return "auth/reset-password";
  }

  /// Traite la soumission du formulaire de réinitialisation de mot de passe.
  ///
  /// @param token      le token de réinitialisation
  /// @param motDePasse le nouveau mot de passe
  /// @param modele     le modèle Thymeleaf
  /// @param response   la réponse HTTP (pour supprimer le cookie SESSION)
  /// @return redirect vers /login en cas de succès, sinon la vue avec erreur
  @PostMapping("/reset-password")
  public String traiterResetPassword(
      @RequestParam String token,
      @RequestParam String motDePasse,
      Model modele,
      HttpServletResponse response) {

    if (motDePasse.length() < 12) {
      modele.addAttribute("erreur", "Le mot de passe doit contenir au moins 12 caractères.");
      modele.addAttribute("token", token);
      return "auth/reset-password";
    }

    List<Map<String, Object>> verifs =
        jdbc.queryForList(
            "SELECT identifier FROM verifications WHERE value = :token AND expires_at > NOW()",
            Map.of("token", token));

    if (verifs.isEmpty()) {
      modele.addAttribute("erreur", "Lien invalide ou expiré. Recommencez depuis le formulaire.");
      return "auth/forgot-password";
    }

    String email = (String) verifs.get(0).get("identifier");
    String hash = passwordEncoder.encode(motDePasse);

    jdbc.update(
        """
        UPDATE accounts SET password = :hash, updated_at = NOW()
        WHERE user_id = (SELECT id FROM users WHERE email = :email)
        """,
        Map.of("hash", hash, "email", email));

    jdbc.update(
        "UPDATE users SET must_change_credentials = false, updated_at = NOW() WHERE email = :email",
        Map.of("email", email));

    jdbc.update("DELETE FROM verifications WHERE value = :token", Map.of("token", token));

    // Invalider la session existante
    Cookie sessionCookie = new Cookie("SESSION", "");
    sessionCookie.setMaxAge(0);
    sessionCookie.setPath("/");
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
}
