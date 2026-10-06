/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.NotificationDigestService;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : envoi du digest de notifications, tous les jours à 8h
 * (fréquence quotidienne) et le dimanche à 10h (fréquence hebdomadaire,
 * portée par les données du déclencheur).
 *
 * @since 2.0.0
 * @author Kwaite
 */
public class NotifierDigestJob extends QuartzJobBean {

  /** Clé des données du déclencheur portant la fréquence à traiter. */
  public static final String CLE_FREQUENCE = "frequence";

  private NotificationDigestService notificationDigestService;

  /** Constructeur par défaut, instancié par Spring. */
  public NotifierDigestJob() {
    super();
  }

  /**
   * @param notificationDigestService service d'envoi du digest injecté par
   *     Spring
   */
  @Autowired
  public void setNotificationDigestService(
      final NotificationDigestService notificationDigestService) {
    this.notificationDigestService = notificationDigestService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    final JobDataMap donnees = context.getMergedJobDataMap();
    final String frequence = donnees != null ? donnees.getString(CLE_FREQUENCE) : null;
    notificationDigestService.executerDigest(frequence != null ? frequence : "quotidien");
  }
}
