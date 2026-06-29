/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Endpoints REST pour les paramètres utilisateur non couverts par les contrôleurs existants.
///
/// <p>Gère le changement d'email (flux de confirmation), les compteurs artistes/salles
/// et la confirmation du changement d'email depuis le lien envoyé par email.
///
/// @since 2.2.0
/// @author Kwaite
@RestController
@RequestMapping("/api/parametres")
public class ParametresRestController {

  private static final Logger log = LoggerFactory.getLogger(ParametresRestController.class);
  private static final Pattern FORMAT_EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
  private static final SecureRandom RNG = new SecureRandom();

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailService emailService;
  private final ConfigService configService;

  /// @param jdbc          template JDBC paramétré
  /// @param emailService  service d'envoi d'emails
  /// @param configService service de configuration pour lire PUBLIC_BASE_URL
  public ParametresRestController(
      NamedParameterJdbcTemplate jdbc, EmailService emailService, ConfigService configService) {
    this.jdbc = jdbc;
    this.emailService = emailService;
    this.configService = configService;
  }

  /// Retourne les compteurs d'artistes et de salles pour l'utilisateur courant.
  ///
  /// @param auth contexte d'authentification
  /// @return `{nbArtistes, nbSalles}`
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats(Authentication auth) {
    String userId = utilisateur(auth).id();
    Integer nbArtistes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);
    Integer nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);
    return ResponseEntity.ok(
        Map.of(
            "nbArtistes", nbArtistes != null ? nbArtistes : 0,
            "nbSalles", nbSalles != null ? nbSalles : 0));
  }

  /// Déclenche un flux de confirmation pour un changement d'adresse email.
  ///
  /// <p>Envoie un email de confirmation à la nouvelle adresse. L'email est mis à jour
  /// uniquement après validation du lien (24 h) via {@link #confirmerEmail}.
  ///
  /// @param corps   corps JSON avec `email` (nouvelle adresse)
  /// @param auth    contexte d'authentification
  /// @param request la requête HTTP (pour construire le lien de confirmation)
  /// @return 200 `{succes: true}` ou 400/503 `{erreur: "..."}`
  @PostMapping("/email-change")
  public ResponseEntity<Map<String, Object>> emailChange(
      @RequestBody Map<String, String> corps, Authentication auth, HttpServletRequest request) {
    UtilisateurDetails details = utilisateur(auth);
    String emailBrut = corps.getOrDefault("email", "");

    if (emailBrut.isBlank()) {
      return erreur(400, "L'adresse email est requise.");
    }
    String emailNorm = emailBrut.strip().toLowerCase();
    if (!FORMAT_EMAIL.matcher(emailNorm).matches() || emailNorm.length() > 254) {
      return erreur(400, "Format d'email invalide.");
    }
    if (emailNorm.equalsIgnoreCase(details.email())) {
      return erreur(400, "Cette adresse est déjà votre adresse email actuelle.");
    }

    boolean emailPris =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE email = :email AND id != :id",
                Map.of("email", emailNorm, "id", details.id()),
                Integer.class)
            .isEmpty();
    if (emailPris) {
      return erreur(400, "Cette adresse email est déjà utilisée.");
    }

    if (!emailService.estDisponible()) {
      return erreur(
          503, "L'envoi d'email n'est pas configuré. Impossible de confirmer le changement.");
    }

    String token = genererToken(32);
    String prefixe = "email-change:" + details.id();
    jdbc.update("DELETE FROM verifications WHERE identifier = :p", Map.of("p", prefixe));
    jdbc.update(
        """
        INSERT INTO verifications (id, identifier, value, expires_at, created_at, updated_at)
        VALUES (:id, :identifier, :value, :expiresAt, now(), now())
        """,
        Map.of(
            "id", UUID.randomUUID().toString(),
            "identifier", prefixe,
            "value", sha256Hex(token),
            "expiresAt", java.sql.Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))));
    jdbc.update(
        "UPDATE users SET pending_email = :email WHERE id = :id",
        Map.of("email", emailNorm, "id", details.id()));

    String lien = baseUrl(request) + "/api/parametres/confirmer-email?token=" + token;
    emailService.envoyerConfirmationEmail(emailNorm, lien);

    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Confirme le changement d'email via le token envoyé par email.
  ///
  /// <p>L'utilisateur doit être connecté (session active). En cas de succès,
  /// redirige vers `/parametres?succes=email-confirme#compte`.
  ///
  /// @param token     le token de confirmation (paramètre URL)
  /// @param auth      contexte d'authentification
  /// @return redirect 302 vers `/parametres?succes=email-confirme#compte` ou une page d'erreur
  @GetMapping("/confirmer-email")
  public ResponseEntity<Void> confirmerEmail(
      @RequestParam(required = false) String token, Authentication auth) {
    if (token == null || token.isBlank()) {
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=lien-invalide#compte"))
          .build();
    }

    List<Map<String, Object>> verifs =
        jdbc.queryForList(
            """
            SELECT identifier FROM verifications
            WHERE value = :hash AND expires_at > NOW()
            """,
            Map.of("hash", sha256Hex(token)));

    if (verifs.isEmpty()) {
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=lien-expire#compte"))
          .build();
    }

    String identifier = (String) verifs.get(0).get("identifier");
    String userId = identifier.contains(":") ? identifier.split(":", 2)[1] : null;
    if (userId == null) {
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=lien-invalide#compte"))
          .build();
    }

    String userIdAuthentifie = utilisateur(auth).id();
    if (!userId.equals(userIdAuthentifie)) {
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=lien-invalide#compte"))
          .build();
    }

    List<String> pendingEmails =
        jdbc.queryForList(
            "SELECT pending_email FROM users WHERE id = :id", Map.of("id", userId), String.class);

    if (pendingEmails.isEmpty() || pendingEmails.get(0) == null) {
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=aucun-changement-email#compte"))
          .build();
    }
    String nouvelEmail = pendingEmails.get(0);

    boolean emailPris =
        !jdbc.queryForList(
                "SELECT 1 FROM users WHERE email = :email AND id != :id",
                Map.of("email", nouvelEmail, "id", userId),
                Integer.class)
            .isEmpty();
    if (emailPris) {
      jdbc.update("UPDATE users SET pending_email = NULL WHERE id = :id", Map.of("id", userId));
      jdbc.update(
          "DELETE FROM verifications WHERE identifier = :p", Map.of("p", "email-change:" + userId));
      return ResponseEntity.status(302)
          .location(URI.create("/parametres?erreur=email-deja-utilise#compte"))
          .build();
    }

    jdbc.update(
        "UPDATE users SET email = :email, pending_email = NULL, updated_at = now() WHERE id = :id",
        Map.of("email", nouvelEmail, "id", userId));
    jdbc.update(
        "DELETE FROM verifications WHERE identifier = :p", Map.of("p", "email-change:" + userId));

    return ResponseEntity.status(302)
        .location(URI.create("/parametres?succes=email-confirme#compte"))
        .build();
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static String genererToken(int nbOctets) {
    byte[] octets = new byte[nbOctets];
    RNG.nextBytes(octets);
    StringBuilder sb = new StringBuilder();
    for (byte b : octets) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 non disponible", e);
    }
  }

  private String baseUrl(HttpServletRequest request) {
    return configService
        .lireConfig("PUBLIC_BASE_URL")
        .filter(s -> !s.isBlank())
        .orElseGet(
            () -> {
              log.warn(
                  "PUBLIC_BASE_URL absente — URL construite depuis la requête (risque Host Header"
                      + " Injection si derrière un proxy)");
              String scheme = request.getScheme();
              String host = request.getServerName();
              int port = request.getServerPort();
              boolean portDefaut =
                  ("http".equals(scheme) && port == 80)
                      || ("https".equals(scheme) && port == 443);
              return scheme
                  + "://"
                  + host
                  + (portDefaut ? "" : ":" + port)
                  + request.getContextPath();
            });
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
