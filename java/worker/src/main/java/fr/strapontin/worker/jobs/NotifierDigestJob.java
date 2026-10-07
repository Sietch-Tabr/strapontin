/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.AvertissementCanalPerduService;
import fr.strapontin.worker.NotificationDigestService;
import java.util.Map;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : digest de notifications (quotidien à 8h, hebdomadaire le
 * dimanche à 10h, voir {@link #CLE_FREQUENCE}), puis avertissement des
 * comptes privés de leur dernier canal.
 * @since 2.0.0
 * @author Kwaite
 */
public class NotifierDigestJob extends QuartzJobBean {

  /** Clé des données du déclencheur portant la fréquence à traiter. */
  public static final String CLE_FREQUENCE = "frequence";

  private NotificationDigestService notificationDigestService;
  private AvertissementCanalPerduService avertissementCanalPerdu;

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

  /**
   * @param avertissementCanalPerdu avertissement des comptes privés de leur
   *     dernier canal, injecté par Spring
   */
  @Autowired
  public void setAvertissementCanalPerdu(
      final AvertissementCanalPerduService avertissementCanalPerdu) {
    this.avertissementCanalPerdu = avertissementCanalPerdu;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    final Map<String, Object> donnees = context.getMergedJobDataMap();
    final Object frequence = donnees != null ? donnees.get(CLE_FREQUENCE) : null;
    notificationDigestService.executerDigest(
        frequence instanceof String valeur ? valeur : "quotidien");
    if (avertissementCanalPerdu != null) {
      avertissementCanalPerdu.verifier();
    }
  }
}
