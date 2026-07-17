/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Job de révocation des admins dont le préavis est écoulé — extrait de {@code WorkerService}
/// (#915).
///
/// Délégué par le job Quartz {@code RevoquerAdminsJob}.
///
/// @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
@Service
public class WorkerRevocationService {

  private static final Logger log = LoggerFactory.getLogger(WorkerRevocationService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;

  public WorkerRevocationService(
      NamedParameterJdbcTemplate jdbc, WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
  }

  /// Révoque les admins dont le délai de préavis est écoulé.
  ///
  /// <p>Passe de `admin` à `user` tous les comptes dont `revocation_admin_le` est dans le passé,
  /// puis révoque leurs sessions actives (#809) — sans cela, un admin rétrogradé resterait
  /// authentifié avec son ancien rôle `admin` (chargé en session à la connexion, jamais
  /// rafraîchi) jusqu'à expiration naturelle de sa session (30 jours). Le module worker n'a pas
  /// le bean {@code FindByIndexNameSessionRepository} (spécifique à Spring Session côté web) :
  /// suppression directe des lignes {@code SPRING_SESSION} par {@code PRINCIPAL_NAME}, table
  /// déjà lue en lecture seule ailleurs (voir {@code AdminService.listerUtilisateurs}).
  /// Loggue le bilan dans `logs_admin`.
  public void executerRevoquerAdmins() {
    log.info("revoquer:admins démarré");
    long debut = System.currentTimeMillis();
    try {
      List<String> usernamesRevoques =
          jdbc.queryForList(
              "SELECT username FROM users WHERE revocation_admin_le < now() AND role = 'admin'",
              Map.of(),
              String.class);
      int nb =
          jdbc.update(
              """
              UPDATE users SET role = 'user', revocation_admin_le = NULL
              WHERE revocation_admin_le < now() AND role = 'admin'
              """,
              Map.of());
      log.info("revoquer:admins terminé nb={}", nb);
      if (nb > 0) {
        revoquerSessions(usernamesRevoques);
        jdbc.update(
            """
            INSERT INTO logs_admin (ts, niveau, source, message, meta)
            VALUES (:ts, :niveau, :source, :message, CAST(:meta AS jsonb))
            """,
            Map.of(
                "ts", System.currentTimeMillis(),
                "niveau", "warn",
                "source", "admin",
                "message", "révocation admin effective — " + nb + " compte(s) rétrogradé(s)",
                "meta", "{}"));
      }
      workerStatsService.enregistrerStatJob(
          "revoquer:admins",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, nb),
          null);
    } catch (Exception e) {
      log.error("revoquer:admins échoué", e);
      workerStatsService.enregistrerStatJob(
          "revoquer:admins",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les sessions Spring Session actives pour les identifiants fournis (#809).
  ///
  /// @param usernames identifiants (colonne {@code username}) des comptes venant d'être rétrogradés
  private void revoquerSessions(List<String> usernames) {
    if (usernames.isEmpty()) return;
    try {
      int supprimees =
          jdbc.update(
              "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME IN (:usernames)",
              Map.of("usernames", usernames));
      if (supprimees > 0) {
        log.info("revoquer:admins — sessions révoquées count={}", supprimees);
      }
    } catch (Exception e) {
      log.warn("revoquer:admins — impossible de révoquer les sessions : {}", e.getMessage());
    }
  }
}
