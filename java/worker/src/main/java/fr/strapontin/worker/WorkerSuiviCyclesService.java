/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Détecte, par type de job planifié, une absence prolongée et
 * inexpliquée d'exécution (détail : CLAUDE.md, table Jobs background,
 * SuiviCyclesJobsJob).
 * @since 2.8.3
 * @author Kwaite
 */
@Service
public class WorkerSuiviCyclesService {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerSuiviCyclesService.class);

  // Fraîcheur maximale attendue par type de job, dérivée du cadencement réel de
  // Planificateur (marge x2 sur l'écart maximal normal entre deux
  // déclenchements consécutifs) — à réaligner si les cron expressions de
  // Planificateur changent.
  private static final Map<String, Duration> FRAICHEUR_ATTENDUE =
      Map.ofEntries(
          Map.entry("fetch:artistes:batch", Duration.ofHours(8)),
          Map.entry("fetch:salles:all", Duration.ofHours(8)),
          Map.entry("recalculer:matches", Duration.ofHours(8)),
          Map.entry("fetch:region", Duration.ofHours(48)),
          Map.entry("purger:concerts", Duration.ofHours(48)),
          Map.entry("purger:concerts-non-confirmes", Duration.ofHours(48)),
          Map.entry("purger:salles", Duration.ofHours(48)),
          Map.entry("purger:artistes", Duration.ofHours(48)),
          Map.entry("purger:logs-admin", Duration.ofHours(48)),
          Map.entry("notifier:digest", Duration.ofHours(48)),
          Map.entry("revoquer:admins", Duration.ofHours(4)),
          Map.entry("reconciliation:nocturne", Duration.ofHours(48)));

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;
  private final WorkerStatsService workerStatsService;

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation des cycles perdus détectés
   * @param workerStatsService lecture de la dernière exécution par type de job
   */
  public WorkerSuiviCyclesService(
      final NamedParameterJdbcTemplate jdbc,
      final LogsAdminWorker logsAdminWorker,
      final WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.workerStatsService = workerStatsService;
  }

  /**
   * Compare, pour chaque type de job connu, la dernière exécution enregistrée à
   * la fraîcheur attendue et journalise un warn pour chaque type resté
   * silencieux au-delà.
   */
  public void executerSuiviCycles() {
    LOG.info("suivi:cycles-jobs démarré");
    workerStatsService.executerAvecStats(
        LOG,
        "suivi:cycles-jobs",
        () -> {
          final int enRetard = verifier();
          LOG.info("suivi:cycles-jobs terminé enRetard={}", enRetard);
          return new ResultatPersistance(0, enRetard);
        });
  }

  /**
   * @return nombre de types de job détectés en retard
   */
  /* package-private : visibilité élargie pour WorkerSuiviCyclesServiceTest
   * (même package). */
  int verifier() {
    final Map<String, Instant> dernieresExecs = lireDernieresExecs();
    int enRetard = 0;
    for (final Map.Entry<String, Duration> entree : FRAICHEUR_ATTENDUE.entrySet()) {
      final String typeJob = entree.getKey();
      final Duration fraicheurMax = entree.getValue();
      final Instant derniereExec = dernieresExecs.get(typeJob);
      // Jamais exécuté depuis le démarrage (déploiement récent) — hors
      // périmètre, aucune référence pour juger d'un retard.
      if (derniereExec == null) {
        continue;
      }
      final Duration ecart = Duration.between(derniereExec, Instant.now());
      if (ecart.compareTo(fraicheurMax) > 0) {
        enRetard++;
        signalerRetard(typeJob, derniereExec, ecart, fraicheurMax);
      }
    }
    return enRetard;
  }

  private Map<String, Instant> lireDernieresExecs() {
    return jdbc.queryForList("SELECT type_job, derniere_exec FROM fetch_jobs", Map.of()).stream()
        .filter(ligne -> ligne.get("derniere_exec") != null)
        .collect(
            Collectors.toMap(
                ligne -> (String) ligne.get("type_job"),
                ligne -> ((Timestamp) ligne.get("derniere_exec")).toInstant()));
  }

  private void signalerRetard(
      final String typeJob,
      final Instant derniereExec,
      final Duration ecart,
      final Duration fraicheurMax) {
    logsAdminWorker.inserer(
        "warn",
        "worker",
        "cycle "
            + typeJob
            + " silencieusement perdu — dernière exécution il y a "
            + ecart.toHours()
            + "h (attendu ≤ "
            + fraicheurMax.toHours()
            + "h)",
        Map.of(
            "typeJob", typeJob,
            "derniereExec", derniereExec.toString(),
            "ecartHeures", ecart.toHours()));
  }
}
