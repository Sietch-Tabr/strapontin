/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de gestion des abonnements Web Push.
///
/// <p>Limite à 10 abonnements par utilisateur. Vérifie la clé VAPID avant d'abonner.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class PushService {

  private static final int CAP_ABONNEMENTS = 10;
  private static final String CLE_USER_ID = "userId";

  private final ConfigService configService;
  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /// @param configService   lecture de la clé VAPID publique
  /// @param jdbc            accès JDBC
  /// @param logAdminService service de logs admin
  public PushService(
      ConfigService configService,
      NamedParameterJdbcTemplate jdbc,
      LogAdminService logAdminService) {
    this.configService = configService;
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Retourne la clé VAPID publique si configurée.
  ///
  /// @return réponse avec clé ou {@code disponible: false}
  public ReponseVapid obtenirCleVapid() {
    String clePublique = configService.lireConfig("VAPID_PUBLIC_KEY").orElse(null);
    return clePublique != null
        ? new ReponseVapid(true, clePublique)
        : new ReponseVapid(false, null);
  }

  /// Enregistre un nouvel abonnement push.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param requete données de l'abonnement
  /// @throws ErreurMetier 400 si données incomplètes/invalides, 429 si cap atteint
  public void abonner(String userId, RequeteAbonnement requete) {
    if (requete.endpoint() == null
        || requete.keys() == null
        || requete.keys().p256dh() == null
        || requete.keys().auth() == null) {
      throw new ErreurMetier("Abonnement incomplet.");
    }
    if (requete.endpoint().length() > 2048
        || requete.keys().p256dh().length() > 256
        || requete.keys().auth().length() > 256) {
      throw new ErreurMetier("Données d'abonnement invalides.");
    }

    int total =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM push_abonnements WHERE user_id = :userId",
                Map.of(CLE_USER_ID, userId),
                Integer.class),
            0);
    if (total >= CAP_ABONNEMENTS) {
      throw new ErreurMetier("Trop d'abonnements push pour cet utilisateur.", 429);
    }

    jdbc.update(
        """
        INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)
        VALUES (:userId, :endpoint, :p256dh, :auth)
        ON CONFLICT (user_id, endpoint) DO NOTHING
        """,
        Map.of(
            CLE_USER_ID, userId,
            "endpoint", requete.endpoint(),
            "p256dh", requete.keys().p256dh(),
            "auth", requete.keys().auth()));

    logAdminService.logAdmin("info", "push", "abonnement push créé", Map.of(CLE_USER_ID, userId));
  }

  /// Supprime un abonnement push.
  ///
  /// @param userId   identifiant de l'utilisateur
  /// @param endpoint URL de l'endpoint push à supprimer
  public void desabonner(String userId, String endpoint) {
    jdbc.update(
        "DELETE FROM push_abonnements WHERE user_id = :userId AND endpoint = :endpoint",
        Map.of(CLE_USER_ID, userId, "endpoint", endpoint));
    logAdminService.logAdmin(
        "info", "push", "abonnement push révoqué", Map.of(CLE_USER_ID, userId));
  }
}
