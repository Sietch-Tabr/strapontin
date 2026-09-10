/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.FetchSallesAllJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/// Retry ciblé pour les 3 jobs multi-lots (`fetch:artistes:batch`/`fetch:region`/
/// `fetch:salles:all`) : sur échec partiel d'un cycle, planifie un déclenchement Quartz ponctuel
/// (+15 min) portant uniquement les identifiants en échec, plutôt que d'attendre le prochain cycle
/// complet ou de marteler indéfiniment un échec déterministe.
///
/// Le trigger de retry cible la {@link JobKey} déjà enregistrée du job cron normal (`storeDurably`,
/// voir {@code Planificateur}) plutôt que de créer un nouveau {@code JobDetail} — sans cela, un
/// retry planifié sous une identité distincte contournerait `@DisallowConcurrentExecution` et
/// pourrait s'exécuter en parallèle du prochain cycle cron normal.
///
/// @since 2.9.0
/// @author Kwaite
@Service
public class RetryLotsService {

  private static final Logger log = LoggerFactory.getLogger(RetryLotsService.class);

  private static final long DELAI_RETRY_MINUTES = 15;

  private record ConfigRetry(JobKey jobKey, String cleJobData) {}

  private static final Map<String, ConfigRetry> CONFIGS_PAR_TYPE_JOB =
      Map.of(
          "fetch:artistes:batch",
              new ConfigRetry(
                  JobKey.jobKey("fetchArtistesBatch"), FetchArtistesBatchJob.CLE_IDS_RETRY),
          "fetch:region",
              new ConfigRetry(JobKey.jobKey("fetchRegion"), FetchRegionJob.CLE_IDS_RETRY),
          "fetch:salles:all",
              new ConfigRetry(JobKey.jobKey("fetchSallesAll"), FetchSallesAllJob.CLE_IDS_RETRY));

  private final Scheduler scheduler;
  private final SuiviChroniciteService suiviChronicite;
  private final LogsAdminWorker logsAdminWorker;

  public RetryLotsService(
      Scheduler scheduler,
      SuiviChroniciteService suiviChronicite,
      LogsAdminWorker logsAdminWorker) {
    this.scheduler = scheduler;
    this.suiviChronicite = suiviChronicite;
    this.logsAdminWorker = logsAdminWorker;
  }

  /// Décide, pour un job multi-lots, s'il faut planifier un retry ciblé, abandonner (chronique) ou
  /// simplement remettre à zéro le compteur de tentatives (cycle propre).
  ///
  /// @param typeJob type de job (ex. {@code "fetch:region"}) — ignoré si non concerné par le retry
  ///   ciblé (jobs hors des 3 jobs multi-lots)
  /// @param identifiantsEnEchec identifiants des entités des lots en échec de ce cycle, vide si le
  ///   cycle est propre
  public void gererResultat(String typeJob, List<String> identifiantsEnEchec) {
    ConfigRetry config = CONFIGS_PAR_TYPE_JOB.get(typeJob);
    if (config == null) return;

    String cleChronicite = "retry:" + typeJob;
    if (identifiantsEnEchec.isEmpty()) {
      suiviChronicite.enregistrerCycle(cleChronicite, false);
      return;
    }

    int streak = suiviChronicite.enregistrerCycle(cleChronicite, true);
    if (streak >= SuiviChroniciteService.SEUIL_CYCLES_CHRONIQUE) {
      logsAdminWorker.inserer(
          "warn",
          "worker",
          suiviChronicite.enrichirSiChronique(
              typeJob + " — retry ciblé abandonné après " + (streak - 1) + " tentative(s)", streak),
          Map.of("typeJob", typeJob, "identifiants", identifiantsEnEchec));
      return;
    }
    programmerRetry(typeJob, config, identifiantsEnEchec);
  }

  private void programmerRetry(String typeJob, ConfigRetry config, List<String> identifiants) {
    try {
      Trigger trigger =
          TriggerBuilder.newTrigger()
              .forJob(config.jobKey())
              .withIdentity("retry-" + typeJob + "-" + System.currentTimeMillis(), "retry")
              .usingJobData(config.cleJobData(), String.join(",", identifiants))
              .startAt(Date.from(Instant.now().plus(DELAI_RETRY_MINUTES, ChronoUnit.MINUTES)))
              .build();
      scheduler.scheduleJob(trigger);
      log.info(
          "retry:lots programmé typeJob={} identifiants={} dans={}min",
          typeJob,
          identifiants.size(),
          DELAI_RETRY_MINUTES);
    } catch (SchedulerException e) {
      log.warn("retry:lots échec de planification typeJob={}", typeJob, e);
    }
  }
}
