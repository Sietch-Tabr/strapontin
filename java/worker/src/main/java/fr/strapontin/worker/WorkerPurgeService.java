/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Jobs de purge (concerts, salles, artistes orphelins, logs admin) — extrait de
/// {@code WorkerService} (#915).
///
/// Délégué par les jobs Quartz {@code PurgerConcertsJob}, {@code PurgerSallesJob},
/// {@code PurgerArtistesJob}, {@code PurgerLogsAdminJob}.
///
/// @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
@Service
public class WorkerPurgeService {

  private static final Logger log = LoggerFactory.getLogger(WorkerPurgeService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;

  public WorkerPurgeService(
      NamedParameterJdbcTemplate jdbc, WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
  }

  /// Supprime les concerts passés depuis plus de 7 jours.
  public void executerPurgerConcerts() {
    LocalDate dateLimite = LocalDate.now().minusDays(7);
    log.info("purger:concerts dateLimite={}", dateLimite);
    long debut = System.currentTimeMillis();
    try {
      int supprimes =
          jdbc.update(
              "DELETE FROM concerts WHERE date < :dateLimite", Map.of("dateLimite", dateLimite));
      log.info("purger:concerts terminé supprimes={}", supprimes);
      workerStatsService.enregistrerStatJob(
          "purger:concerts",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      log.error("purger:concerts échoué", e);
      workerStatsService.enregistrerStatJob(
          "purger:concerts",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les salles orphelines (sans concerts futurs, sans utilisateurs et sans slug
  // Digitick).
  ///
  /// Une salle est conservée si :
  /// - elle possède au moins un concert futur (`date >= CURRENT_DATE`), ou
  /// - au moins un utilisateur la suit, ou
  /// - elle a un slug Digitick configuré (salle source Digitick dans `salle_sources`).
  ///
  /// Doit s'exécuter après {@link #executerPurgerConcerts()} pour travailler
  /// sur un jeu de données cohérent (cf. `PurgerSallesJob`).
  ///
  /// <p>Supprime les candidates une par une plutôt qu'en un seul `DELETE` en masse (#1025) : la
  /// suppression d'une salle peut déclencher en cascade (FK `ON DELETE SET NULL`) un
  /// `UPDATE concerts SET salle_id = NULL` qui entre en conflit avec la contrainte unique
  /// {@code uq_concerts_artiste_date_heure_salle} si un doublon TM/Eventim du même concert existe
  /// déjà avec `salle_id = NULL` — un seul cas de ce type faisait jusqu'ici échouer toute la
  /// transaction du `DELETE` en masse, bloquant la purge de **toutes** les salles orphelines
  /// légitimes, pas seulement celle en cause.
  public void executerPurgerSalles() {
    log.info("purger:salles démarré");
    long debut = System.currentTimeMillis();
    try {
      List<UUID> candidats =
          jdbc.query(
              """
              SELECT id FROM salles
              WHERE NOT EXISTS (
                  SELECT 1 FROM concerts WHERE salle_id = salles.id AND date >= CURRENT_DATE)
                AND NOT EXISTS (
                  SELECT 1 FROM user_salles WHERE salle_id = salles.id)
                AND NOT EXISTS (
                  SELECT 1 FROM salle_sources WHERE salle_id = salles.id AND source = 'digitick')
              """,
              Map.of(),
              (rs, _) -> rs.getObject("id", UUID.class));

      int supprimes = 0;
      for (UUID id : candidats) {
        try {
          jdbc.update("DELETE FROM salles WHERE id = CAST(:id AS uuid)", Map.of("id", id));
          supprimes++;
        } catch (DuplicateKeyException e) {
          log.warn(
              "purger:salles suppression bloquée salleId={} — probable doublon TM/Eventim jamais"
                  + " fusionné (cf. #1025)",
              id);
        }
      }

      log.info("purger:salles terminé supprimes={}", supprimes);
      workerStatsService.enregistrerStatJob(
          "purger:salles",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      log.error("purger:salles échoué", e);
      workerStatsService.enregistrerStatJob(
          "purger:salles",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les artistes orphelins (sans concerts futurs ni utilisateurs).
  ///
  /// Un artiste est conservé si :
  /// - il possède au moins un concert futur (`date >= CURRENT_DATE`), ou
  /// - au moins un utilisateur le suit.
  ///
  /// Doit s'exécuter après {@link #executerPurgerSalles()} (cf. `PurgerArtistesJob`).
  public void executerPurgerArtistes() {
    log.info("purger:artistes démarré");
    long debut = System.currentTimeMillis();
    try {
      int supprimes =
          jdbc.update(
              """
              DELETE FROM artistes
              WHERE NOT EXISTS (
                  SELECT 1 FROM concerts WHERE artiste_id = artistes.id AND date >= CURRENT_DATE)
                AND NOT EXISTS (
                  SELECT 1 FROM user_artistes WHERE artiste_id = artistes.id)
              """,
              Map.of());
      log.info("purger:artistes terminé supprimes={}", supprimes);
      workerStatsService.enregistrerStatJob(
          "purger:artistes",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimes),
          null);
    } catch (Exception e) {
      log.error("purger:artistes échoué", e);
      workerStatsService.enregistrerStatJob(
          "purger:artistes",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// Supprime les entrées de `logs_admin` plus anciennes que 30 jours,
  /// les entrées de `fetch_jobs_historique` plus anciennes que 7 jours,
  /// et les vérifications (tokens d'invitation, emails de confirmation) expirées
  /// depuis plus de 7 jours.
  ///
  /// Remplace l'ancien plafond de 200 entrées purgé à chaque insertion : la rétention
  /// est désormais temporelle et la purge planifiée (cf. `PurgerLogsAdminJob`).
  public void executerPurgerLogsAdmin() {
    long seuilLogs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    Instant seuilHistorique = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    Instant seuilVerifications = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    log.info("purger:logs-admin seuilLogs={} seuilHistorique={}", seuilLogs, seuilHistorique);
    long debut = System.currentTimeMillis();
    try {
      int supprimesLogs =
          jdbc.update("DELETE FROM logs_admin WHERE ts < :seuil", Map.of("seuil", seuilLogs));
      int supprimesHistorique =
          jdbc.update(
              "DELETE FROM fetch_jobs_historique WHERE exec_le < :seuil",
              Map.of("seuil", Timestamp.from(seuilHistorique)));
      int supprimesVerifications =
          jdbc.update(
              "DELETE FROM verifications WHERE expires_at < :seuil",
              Map.of("seuil", Timestamp.from(seuilVerifications)));
      log.info(
          "purger:logs-admin terminé supprimesLogs={} supprimesHistorique={}"
              + " supprimesVerifications={}",
          supprimesLogs,
          supprimesHistorique,
          supprimesVerifications);
      workerStatsService.enregistrerStatJob(
          "purger:logs-admin",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, supprimesLogs + supprimesHistorique + supprimesVerifications),
          null);
    } catch (Exception e) {
      log.error("purger:logs-admin échoué", e);
      workerStatsService.enregistrerStatJob(
          "purger:logs-admin",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }
}
