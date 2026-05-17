/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour les abonnements Web Push (portage de {@code api/push/+server.ts}).
///
/// <p>{@code GET /api/push} est public (expose la clé VAPID publique au navigateur).
/// Les autres endpoints requièrent une authentification.
/// Limite à 10 abonnements push par utilisateur.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/push")
public class PushController {

  private static final int CAP_ABONNEMENTS = 10;

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public PushController(
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  record ReponseVapid(boolean disponible, String clePublique) {}

  record ClesAbonnement(String p256dh, String auth) {}

  record RequeteAbonnement(String endpoint, ClesAbonnement keys) {}

  record RequeteDesabonnement(String endpoint) {}

  /// Retourne la clé VAPID publique (endpoint public — pas d'authentification requise).
  ///
  /// @return la clé publique ou {@code disponible: false} si non configurée
  @GetMapping
  public ResponseEntity<ReponseVapid> obtenirCleVapid() {
    String clePublique = configService.lireConfig("VAPID_PUBLIC_KEY").orElse(null);
    if (clePublique == null) {
      return ResponseEntity.ok(new ReponseVapid(false, null));
    }
    return ResponseEntity.ok(new ReponseVapid(true, clePublique));
  }

  /// Enregistre un nouvel abonnement push pour l'utilisateur courant.
  ///
  /// @param requete corps JSON avec endpoint et clés p256dh/auth
  /// @param auth    contexte d'authentification
  /// @return 201 si créé, 400/429 en cas d'erreur
  @PostMapping
  public ResponseEntity<Map<String, Object>> abonner(
      @RequestBody RequeteAbonnement requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (requete.endpoint() == null
        || requete.keys() == null
        || requete.keys().p256dh() == null
        || requete.keys().auth() == null) {
      return erreur(400, "Abonnement incomplet.");
    }
    if (requete.endpoint().length() > 2048
        || requete.keys().p256dh().length() > 256
        || requete.keys().auth().length() > 256) {
      return erreur(400, "Données d'abonnement invalides.");
    }

    int total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM push_abonnements WHERE user_id = :userId",
            Map.of("userId", userId),
            Integer.class);

    if (total >= CAP_ABONNEMENTS) {
      return erreur(429, "Trop d'abonnements push pour cet utilisateur.");
    }

    jdbc.update(
        """
        INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)
        VALUES (:userId, :endpoint, :p256dh, :auth)
        ON CONFLICT (user_id, endpoint) DO NOTHING
        """,
        Map.of(
            "userId", userId,
            "endpoint", requete.endpoint(),
            "p256dh", requete.keys().p256dh(),
            "auth", requete.keys().auth()));

    logAdminService.logAdmin("info", "push", "abonnement push créé", Map.of("userId", userId));
    return ResponseEntity.status(201).body(Map.of("ok", true));
  }

  /// Supprime un abonnement push de l'utilisateur courant.
  ///
  /// @param requete corps JSON avec l'endpoint à supprimer
  /// @param auth    contexte d'authentification
  /// @return 200 si supprimé, 400 si endpoint manquant
  @DeleteMapping
  public ResponseEntity<Map<String, Object>> desabonner(
      @RequestBody RequeteDesabonnement requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (requete.endpoint() == null || requete.endpoint().isBlank()) {
      return erreur(400, "Endpoint manquant.");
    }

    jdbc.update(
        """
        DELETE FROM push_abonnements
        WHERE user_id = :userId AND endpoint = :endpoint
        """,
        Map.of("userId", userId, "endpoint", requete.endpoint()));

    logAdminService.logAdmin("info", "push", "abonnement push révoqué", Map.of("userId", userId));
    return ResponseEntity.ok(Map.of("ok", true));
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
