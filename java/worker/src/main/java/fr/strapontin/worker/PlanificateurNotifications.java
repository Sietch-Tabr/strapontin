/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.worker.jobs.NotifierDigestJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Planification du digest de notifications : quotidien à 8h, hebdomadaire
 * le dimanche à 10h (fréquence portée par les données du déclencheur).
 * @since 3.0.3
 * @author Kwaite
 */
@Configuration
public class PlanificateurNotifications {

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
    return Planificateur.creerTrigger(
        jobDetailNotifierDigest, "triggerNotifierDigest", "0 0 8 * * ?");
  }

  /* package-private : Bean Spring, jamais appelé directement hors du
   * conteneur. */
  @Bean
  Trigger triggerNotifierDigestHebdomadaire(final JobDetail jobDetailNotifierDigest) {
    return TriggerBuilder.newTrigger()
        .forJob(jobDetailNotifierDigest)
        .withIdentity("triggerNotifierDigestHebdomadaire")
        .usingJobData(NotifierDigestJob.CLE_FREQUENCE, "hebdomadaire")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 10 ? * SUN"))
        .build();
  }
}
