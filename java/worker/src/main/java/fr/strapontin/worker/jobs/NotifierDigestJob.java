/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : envoi du digest de notifications, tous les jours à 8h.
///
/// <p>Les notifications email et push sont implémentées en Phase 4.
///
/// @since 2.0.0
/// @author Kwaite
public class NotifierDigestJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(NotifierDigestJob.class);

  @Autowired private WorkerService workerService;

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerNotifierDigest();
    } catch (Exception e) {
      log.error("notifier:digest échoué", e);
      throw e;
    }
  }
}
