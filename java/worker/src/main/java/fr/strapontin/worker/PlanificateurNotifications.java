/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.NotifierDigestJob;
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
 * (22h à 7h).
 * @since 3.0.3
 * @author Kwaite
 */
@Configuration
public class PlanificateurNotifications {

  private static final TimeZone PARIS = TimeZone.getTimeZone(PlageSilencieusePush.FUSEAU);

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
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 12 * * ?").inTimeZone(PARIS))
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
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 7 * * ?").inTimeZone(PARIS))
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
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 12 ? * SUN").inTimeZone(PARIS))
        .build();
  }
}
