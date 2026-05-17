/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.NotificationDigestService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : envoi du digest de notifications, tous les jours à 8h.
///
/// @since 2.0.0
/// @author Kwaite
public class NotifierDigestJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(NotifierDigestJob.class);

  @Autowired private NotificationDigestService notificationDigestService;

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      notificationDigestService.executerDigest();
    } catch (Exception e) {
      log.error("notifier:digest échoué", e);
      throw e;
    }
  }
}
