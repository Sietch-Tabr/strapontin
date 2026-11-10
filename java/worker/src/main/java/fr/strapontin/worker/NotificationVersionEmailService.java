/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Annonce d'une nouvelle version par email aux utilisateurs qui l'ont demandée
 * et qu'aucun appareil push ne peut atteindre : une seule annonce par version
 * et par utilisateur, quel que soit le canal ({@code notifications_version}),
 * fréquence « jamais » exclue.
 * @since 3.1.4
 * @author Kwaite
 */
@Service
public class NotificationVersionEmailService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationVersionEmailService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final EmailCompteService emailCompteService;
  private final LogsAdminWorker logsAdminWorker;

  /**
   * @param jdbc accès JDBC
   * @param emailCompteService envoi de l'email
   * @param logsAdminWorker bilan de l'envoi
   */
  public NotificationVersionEmailService(
      final NamedParameterJdbcTemplate jdbc,
      final EmailCompteService emailCompteService,
      final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.emailCompteService = emailCompteService;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * À appeler après l'envoi push : un utilisateur déjà prévenu de cette version
   * en est écarté.
   * @param version version applicative à annoncer
   * @return nombre d'utilisateurs prévenus par email
   */
  public int notifierVersion(final String version) {
    final List<Map<String, Object>> destinataires =
        jdbc.queryForList(
            """
            SELECT u.id, u.email
            FROM user_config uc JOIN users u ON u.id = uc.user_id
            WHERE uc.notif_nouvelle_version = true
              AND uc.frequence_notifications <> 'jamais'
              AND u.email IS NOT NULL AND NOT u.must_change_credentials
              AND NOT EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM notifications_version nv
                              WHERE nv.user_id = u.id AND nv.version = :version)
            """,
            Map.of("version", version));
    int prevenus = 0;
    for (final Map<String, Object> destinataire : destinataires) {
      if (emailCompteService.envoyerNouvelleVersion((String) destinataire.get("email"), version)) {
        jdbc.update(
            "INSERT INTO notifications_version (user_id, version) VALUES (:userId, :version)"
                + " ON CONFLICT DO NOTHING",
            Map.of("userId", destinataire.get("id"), "version", version));
        prevenus++;
      }
    }
    if (!destinataires.isEmpty()) {
      LOG.info(
          "annonce de la version {} par email : {}/{}", version, prevenus, destinataires.size());
      logsAdminWorker.inserer(
          "info",
          "notification-version",
          "annonce de nouvelle version par email",
          Map.of(
              "version", version, "nbEnvoyes", prevenus, "nbDestinataires", destinataires.size()));
    }
    return prevenus;
  }
}
