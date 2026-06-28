/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.FetchSallesAllJob;
import fr.strapontin.worker.jobs.NotifierDigestJob;
import fr.strapontin.worker.jobs.PurgerArtistesJob;
import fr.strapontin.worker.jobs.PurgerConcertsJob;
import fr.strapontin.worker.jobs.PurgerLogsAdminJob;
import fr.strapontin.worker.jobs.PurgerSallesJob;
import fr.strapontin.worker.jobs.RecalculerMatchesJob;
import fr.strapontin.worker.jobs.RevoquerAdminsJob;
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
/// - `fetch:artistes:batch` — 11 cycles/jour : 01h, 05h, puis toutes les 2h de 07h à 23h
///   (cf. `worker.cron.fetch-artistes`)
/// - `fetch:region` — mêmes heures + 30 min (cf. `worker.cron.fetch-region`)
/// - `fetch:salles:all` — mêmes heures que fetch:region (cf. `worker.cron.fetch-salles`)
/// - `recalculer:matches` — mêmes heures que fetch:artistes (cf. `worker.cron.recalculer-matches`)
/// - `purger:concerts` — tous les jours à minuit
/// - `purger:salles` — tous les jours à 0h05 (après purge concerts)
/// - `purger:artistes` — tous les jours à 0h06 (après purge salles)
/// - `purger:logs-admin` — tous les jours à 0h15
/// - `notifier:digest` — tous les jours à 8h
/// - `revoquer:admins` — toutes les heures
///
/// Le job `fetch:salle` est planifié dynamiquement par [WorkerService]
/// selon les salles configurées. `fetch:salles:all` est le pendant périodique
/// qui boucle sur toutes les salles ayant des sources configurées.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class Planificateur {

  @Value("${worker.cron.fetch-artistes:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchArtistes;

  @Value("${worker.cron.fetch-region:0 30 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchRegion;

  @Value("${worker.cron.fetch-salles:0 30 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
  private String cronFetchSalles;

  @Value("${worker.cron.recalculer-matches:0 0 1,5,7,9,11,13,15,17,19,21,23 * * ?}")
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
  JobDetail jobDetailFetchSallesAll() {
    return JobBuilder.newJob(FetchSallesAllJob.class)
        .withIdentity("fetchSallesAll")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerFetchSallesAll(JobDetail jobDetailFetchSallesAll) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchSallesAll)
        .withIdentity("triggerFetchSallesAll")
        .withSchedule(CronScheduleBuilder.cronSchedule(cronFetchSalles))
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
  JobDetail jobDetailPurgerSalles() {
    return JobBuilder.newJob(PurgerSallesJob.class)
        .withIdentity("purgerSalles")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerPurgerSalles(JobDetail jobDetailPurgerSalles) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailPurgerSalles)
        .withIdentity("triggerPurgerSalles")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 5 0 * * ?"))
        .build();
  }

  @Bean
  JobDetail jobDetailPurgerArtistes() {
    return JobBuilder.newJob(PurgerArtistesJob.class)
        .withIdentity("purgerArtistes")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerPurgerArtistes(JobDetail jobDetailPurgerArtistes) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailPurgerArtistes)
        .withIdentity("triggerPurgerArtistes")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 6 0 * * ?"))
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

  @Bean
  JobDetail jobDetailRevoquerAdmins() {
    return JobBuilder.newJob(RevoquerAdminsJob.class)
        .withIdentity("revoquerAdmins")
        .storeDurably()
        .build();
  }

  @Bean
  Trigger triggerRevoquerAdmins(JobDetail jobDetailRevoquerAdmins) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailRevoquerAdmins)
        .withIdentity("triggerRevoquerAdmins")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?"))
        .build();
  }
}
