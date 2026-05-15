/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.FetchArtistesBatchJob;
import fr.strapontin.worker.jobs.FetchRegionJob;
import fr.strapontin.worker.jobs.NotifierDigestJob;
import fr.strapontin.worker.jobs.PurgerConcertsJob;
import fr.strapontin.worker.jobs.RecalculerMatchesJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Planification Quartz des jobs récurrents.
///
/// <p>Déclencheurs configurés :
/// <ul>
///   <li>{@code fetch:artistes:batch} — toutes les 2 heures
///   <li>{@code fetch:region} — toutes les 2 heures
///   <li>{@code recalculer:matches} — toutes les 2 heures
///   <li>{@code purger:concerts} — tous les jours à minuit
///   <li>{@code notifier:digest} — tous les jours à 8h
/// </ul>
///
/// <p>Le job {@code fetch:salle} est planifié dynamiquement par {@link WorkerService}
/// selon les salles configurées.
///
/// @since 2.0.0
/// @author Kwaite
@Configuration
public class Planificateur {

  @Bean
  public JobDetail jobDetailFetchArtistesBatch() {
    return JobBuilder.newJob(FetchArtistesBatchJob.class)
        .withIdentity("fetchArtistesBatch")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger triggerFetchArtistesBatch(JobDetail jobDetailFetchArtistesBatch) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchArtistesBatch)
        .withIdentity("triggerFetchArtistesBatch")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0/2 * * ?"))
        .build();
  }

  @Bean
  public JobDetail jobDetailFetchRegion() {
    return JobBuilder.newJob(FetchRegionJob.class)
        .withIdentity("fetchRegion")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger triggerFetchRegion(JobDetail jobDetailFetchRegion) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailFetchRegion)
        .withIdentity("triggerFetchRegion")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 30 0/2 * * ?"))
        .build();
  }

  @Bean
  public JobDetail jobDetailRecalculerMatches() {
    return JobBuilder.newJob(RecalculerMatchesJob.class)
        .withIdentity("recalculerMatches")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger triggerRecalculerMatches(JobDetail jobDetailRecalculerMatches) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailRecalculerMatches)
        .withIdentity("triggerRecalculerMatches")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 1/2 * * ?"))
        .build();
  }

  @Bean
  public JobDetail jobDetailPurgerConcerts() {
    return JobBuilder.newJob(PurgerConcertsJob.class)
        .withIdentity("purgerConcerts")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger triggerPurgerConcerts(JobDetail jobDetailPurgerConcerts) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailPurgerConcerts)
        .withIdentity("triggerPurgerConcerts")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0 * * ?"))
        .build();
  }

  @Bean
  public JobDetail jobDetailNotifierDigest() {
    return JobBuilder.newJob(NotifierDigestJob.class)
        .withIdentity("notifierDigest")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger triggerNotifierDigest(JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierDigest")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8 * * ?"))
        .build();
  }
}
