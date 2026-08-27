/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Détecte, par type de job planifié, une absence prolongée et inexpliquée d'exécution — un cycle
/// silencieusement perdu (reboot brutal de l'hôte, OOM, crash) plutôt qu'un ralentissement normal
/// de cadence.
///
/// Délégué par le job Quartz {@code SuiviCyclesJobsJob}, cadencé toutes les heures.
///
/// <p>Complète {@code WatchdogWorkerService} (module web) : celui-ci n'alerte que sur l'inactivité
/// globale du worker, tous types de job confondus — un type de job resté silencieusement bloqué
/// alors que les autres continuent de s'exécuter normalement ne déclenche jamais cette alerte
/// globale.
///
/// @since 2.8.3
/// @author Kwaite
@Service
public class WorkerSuiviCyclesService {

  private static final Logger log = LoggerFactory.getLogger(WorkerSuiviCyclesService.class);

  // Fraîcheur maximale attendue par type de job, dérivée du cadencement réel de Planificateur
  // (marge x2 sur l'écart maximal normal entre deux déclenchements consécutifs) — à réaligner si
  // les cron expressions de Planificateur changent.
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

  public WorkerSuiviCyclesService(
      NamedParameterJdbcTemplate jdbc,
      LogsAdminWorker logsAdminWorker,
      WorkerStatsService workerStatsService) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
    this.workerStatsService = workerStatsService;
  }

  /// Compare, pour chaque type de job connu, la dernière exécution enregistrée à la fraîcheur
  /// attendue et journalise un warn pour chaque type resté silencieux au-delà.
  public void executerSuiviCycles() {
    log.info("suivi:cycles-jobs démarré");
    long debut = System.currentTimeMillis();
    try {
      int enRetard = verifier();
      log.info("suivi:cycles-jobs terminé enRetard={}", enRetard);
      workerStatsService.enregistrerStatJob(
          "suivi:cycles-jobs",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, enRetard),
          null);
    } catch (Exception e) {
      log.error("suivi:cycles-jobs échoué", e);
      workerStatsService.enregistrerStatJob(
          "suivi:cycles-jobs",
          System.currentTimeMillis() - debut,
          new ResultatPersistance(0, 0),
          e.getMessage());
    }
  }

  /// @return nombre de types de job détectés en retard
  int verifier() {
    Map<String, Instant> dernieresExecs = lireDernieresExecs();
    int enRetard = 0;
    for (var entree : FRAICHEUR_ATTENDUE.entrySet()) {
      String typeJob = entree.getKey();
      Duration fraicheurMax = entree.getValue();
      Instant derniereExec = dernieresExecs.get(typeJob);
      // Jamais exécuté depuis le démarrage (déploiement récent) — hors périmètre, aucune
      // référence pour juger d'un retard.
      if (derniereExec == null) continue;
      Duration ecart = Duration.between(derniereExec, Instant.now());
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
            java.util.stream.Collectors.toMap(
                ligne -> (String) ligne.get("type_job"),
                ligne -> ((java.sql.Timestamp) ligne.get("derniere_exec")).toInstant()));
  }

  private void signalerRetard(
      String typeJob, Instant derniereExec, Duration ecart, Duration fraicheurMax) {
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
