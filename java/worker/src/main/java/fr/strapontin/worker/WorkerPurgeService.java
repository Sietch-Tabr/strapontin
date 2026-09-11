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

/**
 * Jobs de purge (concerts, salles, artistes orphelins, logs admin) — extrait de {@code
 * WorkerService}.
 *
 * <p>Délégué par les jobs Quartz {@code PurgerConcertsJob}, {@code PurgerSallesJob}, {@code
 * PurgerArtistesJob}, {@code PurgerLogsAdminJob}.
 *
 * @since 2.0.0 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
@Service
public class WorkerPurgeService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerPurgeService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final WorkerStatsService workerStatsService;

  /**
   * @param jdbc accès JDBC
   * @param workerStatsService journalisation du bilan de purge
   */
  public WorkerPurgeService(
      final NamedParameterJdbcTemplate jdbc, final WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.workerStatsService = workerStatsService;
  }

  /** Supprime les concerts passés depuis plus de 7 jours. */
  public void executerPurgerConcerts() {
    final LocalDate dateLimite = LocalDate.now().minusDays(7);
    LOG.info("purger:concerts dateLimite={}", dateLimite);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts",
        () -> {
          final int supprimes =
              jdbc.update(
                  "DELETE FROM evenements WHERE date < :dateLimite",
                  Map.of("dateLimite", dateLimite));
          LOG.info("purger:concerts terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  // Seuil de purge définitive d'un concert futur jamais reconfirmé — très supérieur au seuil de
  // simple signalement "à vérifier" (WorkerFetchService.SEUIL_ABSENCES_CONSECUTIVES, une demi-
  // journée de silence) : un état "à vérifier" posé par ce garde-fou anti-faux-positif ne doit pas
  // rester affiché indéfiniment sans jamais évoluer. ×10 correspond à environ 5 jours de silence
  // continu (cron ~11 cycles/jour), largement au-delà de toute panne transitoire de source déjà
  // observée.
  static final int SEUIL_PURGE_ABSENCES = WorkerFetchService.SEUIL_ABSENCES_CONSECUTIVES * 10;

  /**
   * Signale pour suppression les concerts futurs dont le compteur d'absences consécutives dépasse
   * {@value #SEUIL_PURGE_ABSENCES} — un concert signalé "à vérifier" (voir {@code
   * WorkerFetchService.mettreAJourAbsences}) qui n'a plus été confirmé par aucune source depuis
   * bien plus longtemps que le seuil de détection initial n'a manifestement plus de raison d'être
   * affiché indéfiniment tel quel.
   *
   * <p>Pose {@code suppression_suggeree_le} plutôt que de supprimer directement — un doublon de
   * salle jamais fusionné entre sources, ou un concert catalogué sous une autre attraction que
   * l'artiste suivi, peuvent faire grimper ce compteur pour un concert pourtant réel et toujours en
   * vente. La suppression définitive reste une action manuelle, revue depuis l'admin (onglet
   * Concerts).
   */
  public void executerPurgerConcertsNonConfirmes() {
    LOG.info("purger:concerts-non-confirmes seuilAbsences={}", SEUIL_PURGE_ABSENCES);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts-non-confirmes",
        () -> {
          final int signales =
              jdbc.update(
                  "UPDATE evenements SET suppression_suggeree_le = now()"
                      + " WHERE absences_consecutives >= :seuil AND date >= CURRENT_DATE"
                      + " AND suppression_suggeree_le IS NULL",
                  Map.of("seuil", SEUIL_PURGE_ABSENCES));
          LOG.info("purger:concerts-non-confirmes terminé signales={}", signales);
          return new ResultatPersistance(0, signales);
        });
  }

  // Un concert sans salle_id ni artiste_id n'est jamais retouché par aucun job de fetch (tous
  // scopés par artiste suivi, salle suivie ou département suivi) — absences_consecutives reste
  // figé à 0 et ne peut donc jamais atteindre SEUIL_PURGE_ABSENCES. 30 jours sans mise à jour
  // signe une staleness réelle, indépendante de ce compteur inopérant pour ce cas.
  private static final int SEUIL_JOURS_HORS_PORTEE = 30;

  /**
   * Signale pour suppression les concerts futurs sans {@code salle_id} ni {@code artiste_id},
   * jamais réévalués par aucun mécanisme de fetch (tous scopés par entité ou département suivi) et
   * donc jamais atteints par {@link #executerPurgerConcertsNonConfirmes} — plus de {@value
   * #SEUIL_JOURS_HORS_PORTEE} jours sans mise à jour sert de signal de substitution.
   *
   * <p>Même mécanisme non destructif que {@link #executerPurgerConcertsNonConfirmes} : {@code
   * suppression_suggeree_le}, revue manuelle depuis l'admin.
   */
  public void executerPurgerConcertsHorsPortee() {
    LOG.info("purger:concerts-hors-portee seuilJours={}", SEUIL_JOURS_HORS_PORTEE);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:concerts-hors-portee",
        () -> {
          final int signales =
              jdbc.update(
                  "UPDATE evenements SET suppression_suggeree_le = now()"
                      + " WHERE salle_id IS NULL AND artiste_id IS NULL AND date >= CURRENT_DATE"
                      + " AND updated_at < now() - make_interval(days => :seuilJours)"
                      + " AND suppression_suggeree_le IS NULL",
                  Map.of("seuilJours", SEUIL_JOURS_HORS_PORTEE));
          LOG.info("purger:concerts-hors-portee terminé signales={}", signales);
          return new ResultatPersistance(0, signales);
        });
  }

  /**
   * Supprime les salles orphelines (sans concerts futurs, sans utilisateurs et sans slug Digitick).
   * Une salle est conservée si :
   *
   * <ul>
   *   <li>elle possède au moins un concert futur ({@code date >= CURRENT_DATE}), ou
   *   <li>au moins un utilisateur la suit, ou
   *   <li>elle a un slug Digitick configuré (salle source Digitick dans {@code salle_sources}).
   * </ul>
   *
   * <p>Doit s'exécuter après {@link #executerPurgerConcerts()} pour travailler sur un jeu de
   * données cohérent (cf. {@code PurgerSallesJob}).
   *
   * <p>Supprime les candidates une par une plutôt qu'en un seul {@code DELETE} en masse : la
   * suppression d'une salle peut déclencher en cascade (FK {@code ON DELETE SET NULL}) un {@code
   * UPDATE evenements SET salle_id = NULL} qui entre en conflit avec la contrainte unique {@code
   * uq_evenements} si un doublon TM/Eventim du même événement existe déjà avec {@code salle_id =
   * NULL} — un seul cas de ce type faisait jusqu'ici échouer toute la transaction du {@code DELETE}
   * en masse, bloquant la purge de <strong>toutes</strong> les salles orphelines légitimes, pas
   * seulement celle en cause.
   */
  public void executerPurgerSalles() {
    LOG.info("purger:salles démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "purger:salles",
        () -> {
          final List<UUID> candidats =
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
          for (final UUID id : candidats) {
            try {
              jdbc.update("DELETE FROM salles WHERE id = CAST(:id AS uuid)", Map.of("id", id));
              supprimes++;
            } catch (DuplicateKeyException e) {
              LOG.warn(
                  "purger:salles suppression bloquée salleId={} — probable doublon TM/Eventim"
                      + " jamais fusionné",
                  id);
            }
          }

          LOG.info("purger:salles terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  /**
   * Supprime les artistes orphelins (sans concerts futurs ni utilisateurs).
   *
   * <p>Un artiste est conservé si :
   *
   * <ul>
   *   <li>il possède au moins un concert futur ({@code date >= CURRENT_DATE}), ou
   *   <li>au moins un utilisateur le suit.
   * </ul>
   *
   * <p>Doit s'exécuter après {@link #executerPurgerSalles()} (cf. {@code PurgerArtistesJob}).
   */
  public void executerPurgerArtistes() {
    LOG.info("purger:artistes démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "purger:artistes",
        () -> {
          final int supprimes =
              jdbc.update(
                  """
                  DELETE FROM artistes
                  WHERE NOT EXISTS (
                      SELECT 1 FROM concerts WHERE artiste_id = artistes.id AND date >= CURRENT_DATE)
                    AND NOT EXISTS (
                      SELECT 1 FROM user_artistes WHERE artiste_id = artistes.id)
                  """,
                  Map.of());
          LOG.info("purger:artistes terminé supprimes={}", supprimes);
          return new ResultatPersistance(0, supprimes);
        });
  }

  /**
   * Supprime les entrées de {@code logs_admin} plus anciennes que 30 jours, les entrées de {@code
   * fetch_jobs_historique} plus anciennes que 7 jours, et les vérifications (tokens d'invitation,
   * emails de confirmation) expirées depuis plus de 7 jours.
   *
   * <p>Remplace l'ancien plafond de 200 entrées purgé à chaque insertion : la rétention est
   * désormais temporelle et la purge planifiée (cf. {@code PurgerLogsAdminJob}).
   */
  public void executerPurgerLogsAdmin() {
    final long seuilLogs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    final Instant seuilHistorique = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    final Instant seuilVerifications = Instant.now().minusSeconds(7L * 24 * 60 * 60);
    LOG.info("purger:logs-admin seuilLogs={} seuilHistorique={}", seuilLogs, seuilHistorique);
    workerStatsService.executerAvecStats(
        LOG,
        "purger:logs-admin",
        () -> {
          final int supprimesLogs =
              jdbc.update("DELETE FROM logs_admin WHERE ts < :seuil", Map.of("seuil", seuilLogs));
          final int supprimesHistorique =
              jdbc.update(
                  "DELETE FROM fetch_jobs_historique WHERE exec_le < :seuil",
                  Map.of("seuil", Timestamp.from(seuilHistorique)));
          final int supprimesVerifications =
              jdbc.update(
                  "DELETE FROM verifications WHERE expires_at < :seuil",
                  Map.of("seuil", Timestamp.from(seuilVerifications)));
          LOG.info(
              "purger:logs-admin terminé supprimesLogs={} supprimesHistorique={}"
                  + " supprimesVerifications={}",
              supprimesLogs,
              supprimesHistorique,
              supprimesVerifications);
          return new ResultatPersistance(
              0, supprimesLogs + supprimesHistorique + supprimesVerifications);
        });
  }
}
