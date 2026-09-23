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

/**
 * Retry ciblé pour les 3 jobs multi-lots — détail (trigger, JobKey,
 * abandon après 2 retries) : CLAUDE.md, Observabilité des jobs.
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class RetryLotsService {

  private static final Logger LOG = LoggerFactory.getLogger(RetryLotsService.class);

  private static final long DELAI_RETRY_MINUTES = 15;

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

  private record ConfigRetry(JobKey jobKey, String cleJobData) {}

  /**
   * @param scheduler planificateur Quartz portant le job cron normal à
   *     re-déclencher
   * @param suiviChronicite comptage des cycles consécutifs en échec, pour
   *     l'abandon après 2 retries
   * @param logsAdminWorker journalisation des retries planifiés
   */
  public RetryLotsService(
      final Scheduler scheduler,
      final SuiviChroniciteService suiviChronicite,
      final LogsAdminWorker logsAdminWorker) {
    this.scheduler = scheduler;
    this.suiviChronicite = suiviChronicite;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Décide, pour un job multi-lots, s'il faut planifier un retry ciblé,
   * abandonner (chronique) ou remettre à zéro (cycle propre).
   * @param typeJob type de job, ignoré si hors des 3 jobs multi-lots
   * @param identifiantsEnEchec identifiants en échec, vide si cycle propre
   */
  public void gererResultat(final String typeJob, final List<String> identifiantsEnEchec) {
    final ConfigRetry config = CONFIGS_PAR_TYPE_JOB.get(typeJob);
    if (config == null) {
      return;
    }

    final String cleChronicite = "retry:" + typeJob;
    if (identifiantsEnEchec.isEmpty()) {
      suiviChronicite.enregistrerCycle(cleChronicite, false);
      return;
    }

    final int streak = suiviChronicite.enregistrerCycle(cleChronicite, true);
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

  private void programmerRetry(
      final String typeJob, final ConfigRetry config, final List<String> identifiants) {
    try {
      final Trigger trigger =
          TriggerBuilder.newTrigger()
              .forJob(config.jobKey())
              .withIdentity("retry-" + typeJob + "-" + System.currentTimeMillis(), "retry")
              .usingJobData(config.cleJobData(), String.join(",", identifiants))
              .startAt(Date.from(Instant.now().plus(DELAI_RETRY_MINUTES, ChronoUnit.MINUTES)))
              .build();
      scheduler.scheduleJob(trigger);
      LOG.info(
          "retry:lots programmé typeJob={} identifiants={} dans={}min",
          typeJob,
          identifiants.size(),
          DELAI_RETRY_MINUTES);
    } catch (SchedulerException e) {
      LOG.warn("retry:lots échec de planification typeJob={} : {}", typeJob, e.getMessage());
    }
  }
}
