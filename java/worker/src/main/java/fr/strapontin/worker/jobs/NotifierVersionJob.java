/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.NotificationVersionPushService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : envoi push "nouvelle version disponible", déclenché
 * uniquement à la demande depuis l'admin (détail : CLAUDE.md, Jobs
 * background) — jamais planifié sur cron.
 * @since 3.0.0
 * @author Kwaite
 */
public class NotifierVersionJob extends QuartzJobBean {

  public static final String CLE_VERSION = "version";

  private static final Logger LOG = LoggerFactory.getLogger(NotifierVersionJob.class);

  private NotificationVersionPushService notificationVersionPushService;

  /** Constructeur par défaut, instancié par Spring. */
  public NotifierVersionJob() {
    super();
  }

  /**
   * @param notificationVersionPushService service d'envoi injecté par Spring
   */
  @Autowired
  public void setNotificationVersionPushService(
      final NotificationVersionPushService notificationVersionPushService) {
    this.notificationVersionPushService = notificationVersionPushService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    final String version = context.getMergedJobDataMap().getString(CLE_VERSION);
    if (version == null || version.isBlank()) {
      LOG.warn("notifier:version — version absente dans la JobDataMap");
      return;
    }
    try {
      final int nbEnvoyes = notificationVersionPushService.notifierVersion(version);
      LOG.info("notifier:version terminé version={} nbEnvoyes={}", version, nbEnvoyes);
    } catch (Exception e) {
      LOG.error("notifier:version échoué version={}", version, e);
      throw e;
    }
  }
}
