/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import fr.strapontin.core.HorairesJobs;
import fr.strapontin.worker.jobs.NotifierDigestJob;
import fr.strapontin.worker.jobs.NotifierVersionJob;
import fr.strapontin.worker.planification.Planificateur;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Planification du digest de notifications, à l'heure de Paris : quotidien à
 * 12h, hebdomadaire le dimanche à 12h (fréquence portée par les données du
 * déclencheur), et à 7h l'envoi des push retenus pendant la plage silencieuse
 * (22h à 7h) ; à 11h, l'annonce d'une nouvelle version.
 * @since 3.0.3
 * @author Kwaite
 */
@Configuration
public class PlanificateurNotifications {

  private static final TimeZone PARIS = Planificateur.PARIS;

  /** Instancié par Spring. */
  public PlanificateurNotifications() {
    // configuration sans état
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailNotifierDigest() {
    return Planificateur.creerJobDetail(NotifierDigestJob.class, "notifierDigest");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerNotifierDigest(final JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierDigest")
        .withSchedule(
            CronScheduleBuilder.cronSchedule(HorairesJobs.DIGEST_QUOTIDIEN).inTimeZone(PARIS))
        .build();
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerNotifierPushDifferes(final JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierPushDifferes")
        .usingJobData(NotifierDigestJob.CLE_FREQUENCE, NotifierDigestJob.FREQUENCE_PUSH_DIFFERES)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(HorairesJobs.DIGEST_DIFFERES).inTimeZone(PARIS))
        .build();
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  JobDetail jobDetailNotifierVersion() {
    return Planificateur.creerJobDetail(NotifierVersionJob.class, "notifierVersion");
  }

  // Chaque jour à 11h : une version déployée dans la journée est annoncée le
  // lendemain, jamais deux fois au même utilisateur (notifications_version).
  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerNotifierVersion(final JobDetail jobDetailNotifierVersion) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierVersion)
        .withIdentity("triggerNotifierVersion")
        .withSchedule(
            CronScheduleBuilder.cronSchedule(HorairesJobs.NOTIFIER_VERSION).inTimeZone(PARIS))
        .build();
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerNotifierDigestHebdomadaire(final JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierDigestHebdomadaire")
        .usingJobData(NotifierDigestJob.CLE_FREQUENCE, "hebdomadaire")
        .withSchedule(
            CronScheduleBuilder.cronSchedule(HorairesJobs.DIGEST_HEBDOMADAIRE).inTimeZone(PARIS))
        .build();
  }
}
