/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.NotifierDigestJob;
import fr.strapontin.worker.jobs.PurgerConcertsJob;
import fr.strapontin.worker.jobs.PurgerLogsAdminJob;
import fr.strapontin.worker.jobs.RecalculerMatchesJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Planification Quartz des jobs récurrents.
///
/// Déclencheurs configurés :
///
/// - `fetch:artistes:batch` — toutes les 2 heures (cf. `worker.cron.fetch-artistes`)
/// - `fetch:region` — toutes les 2 heures (cf. `worker.cron.fetch-region`)
/// - `recalculer:matches` — toutes les 2 heures (cf. `worker.cron.recalculer-matches`)
/// - `purger:concerts` — tous les jours à minuit
/// - `purger:logs-admin` — tous les jours à 0h15
/// - `notifier:digest` — tous les jours à 8h
///
/// Le job `fetch:salle` est planifié dynamiquement par [WorkerService]
/// selon les salles configurées.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class Planificateur {

  @Value("${worker.cron.fetch-artistes:0 0 0/2 * * ?}")
  private String cronFetchArtistes;

  @Value("${worker.cron.fetch-region:0 30 0/2 * * ?}")
  private String cronFetchRegion;

  @Value("${worker.cron.recalculer-matches:0 0 1/2 * * ?}")
  private String cronRecalculerMatches;

  @Bean
  JobDetail jobDetailFetchArtistesBatch() {
    return JobBuilder.newJob(FetchArtistesBatchJob.class)
        .withIdentity("fetchArtistesBatch")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerFetchArtistesBatch(JobDetail jobDetailFetchArtistesBatch) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchArtistesBatch)
        .withIdentity("triggerFetchArtistesBatch")
        .withSchedule(CronScheduleBuilder.cronSchedule(cronFetchArtistes))
        .build();
  }

  @Bean
  JobDetail jobDetailFetchRegion() {
    return JobBuilder.newJob(FetchRegionJob.class)
        .withIdentity("fetchRegion")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerFetchRegion(JobDetail jobDetailFetchRegion) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchRegion)
        .withIdentity("triggerFetchRegion")
        .withSchedule(CronScheduleBuilder.cronSchedule(cronFetchRegion))
        .build();
  }

  @Bean
  JobDetail jobDetailRecalculerMatches() {
    return JobBuilder.newJob(RecalculerMatchesJob.class)
        .withIdentity("recalculerMatches")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerRecalculerMatches(JobDetail jobDetailRecalculerMatches) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailRecalculerMatches)
        .withIdentity("triggerRecalculerMatches")
        .withSchedule(CronScheduleBuilder.cronSchedule(cronRecalculerMatches))
        .build();
  }

  @Bean
  JobDetail jobDetailPurgerConcerts() {
    return JobBuilder.newJob(PurgerConcertsJob.class)
        .withIdentity("purgerConcerts")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerPurgerConcerts(JobDetail jobDetailPurgerConcerts) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailPurgerConcerts)
        .withIdentity("triggerPurgerConcerts")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0 * * ?"))
        .build();
  }

  @Bean
  JobDetail jobDetailPurgerLogsAdmin() {
    return JobBuilder.newJob(PurgerLogsAdminJob.class)
        .withIdentity("purgerLogsAdmin")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerPurgerLogsAdmin(JobDetail jobDetailPurgerLogsAdmin) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailPurgerLogsAdmin)
        .withIdentity("triggerPurgerLogsAdmin")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 15 0 * * ?"))
        .build();
  }

  @Bean
  JobDetail jobDetailNotifierDigest() {
    return JobBuilder.newJob(NotifierDigestJob.class)
        .withIdentity("notifierDigest")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerNotifierDigest(JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierDigest")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8 * * ?"))
        .build();
  }
}
