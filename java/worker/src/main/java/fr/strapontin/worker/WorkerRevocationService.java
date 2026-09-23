/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Job de révocation des admins dont le préavis est écoulé — extrait de
 * {@code WorkerService}, délégué par le job Quartz {@code RevoquerAdminsJob}.
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class WorkerRevocationService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerRevocationService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;

  /**
   * @param jdbc accès JDBC
   * @param workerStatsService journalisation du bilan de révocation
   */
  public WorkerRevocationService(
      final NamedParameterJdbcTemplate jdbc, final WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
  }

  /**
   * Révoque les admins dont le délai de préavis est écoulé — détail
   * (rôle rétrogradé, sessions révoquées) : CLAUDE.md,
   * Sécurité admin/sessions.
   */
  public void executerRevoquerAdmins() {
    LOG.info("revoquer:admins démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "revoquer:admins",
        () -> {
          final List<String> usernamesRevoques =
              jdbc.queryForList(
                  "SELECT username FROM users WHERE revocation_admin_le < now() AND role ="
                      + " 'admin'",
                  Map.of(),
                  String.class);
          final int nb =
              jdbc.update(
                  """
                  UPDATE users SET role = 'user', revocation_admin_le = NULL
                  WHERE revocation_admin_le < now() AND role = 'admin'
                  """,
                  Map.of());
          LOG.info("revoquer:admins terminé nb={}", nb);
          if (nb > 0) {
            revoquerSessions(usernamesRevoques);
            jdbc.update(
                """
                INSERT INTO logs_admin (ts, niveau, source, message, meta)
                VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
                """,
                Map.of(
                    "ts", Timestamp.from(Instant.now()),
                    "niveau", "warn",
                    "source", "admin",
                    "message", "révocation admin effective — " + nb + " compte(s) rétrogradé(s)",
                    "meta", "{}"));
          }
          return new ResultatPersistance(0, nb);
        });
  }

  /**
   * Supprime les sessions Spring Session actives pour les identifiants fournis.
   *
   * @param usernames identifiants (colonne {@code username} ) des comptes
   *     venant d'être rétrogradés
   */
  private void revoquerSessions(final List<String> usernames) {
    if (usernames.isEmpty()) {
      return;
    }
    try {
      final int supprimees =
          jdbc.update(
              "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME IN (:usernames)",
              Map.of("usernames", usernames));
      if (supprimees > 0) {
        LOG.info("revoquer:admins — sessions révoquées count={}", supprimees);
      }
    } catch (Exception e) {
      LOG.warn("revoquer:admins — impossible de révoquer les sessions : {}", e.getMessage());
    }
  }
}
