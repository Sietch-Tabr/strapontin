/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.notification.AvertissementCanalPerduService;
import fr.strapontin.worker.notification.NotificationDigestService;
import java.util.Map;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : digest de notifications (quotidien à 12h, hebdomadaire le
 * dimanche à 12h, voir {@link #CLE_FREQUENCE}), puis avertissement des
 * comptes privés de leur dernier canal ; à 7h, envoi des push retenus
 * pendant la plage silencieuse.
 * @since 2.0.0
 * @author Kwaite
 */
public class NotifierDigestJob extends QuartzJobBean {

  /** Clé des données du déclencheur portant la fréquence à traiter. */
  public static final String CLE_FREQUENCE = "frequence";

  /** Valeur de {@link #CLE_FREQUENCE} qui envoie les push retenus pendant la nuit. */
  public static final String FREQUENCE_PUSH_DIFFERES = "differees";

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
    if (FREQUENCE_PUSH_DIFFERES.equals(frequence)) {
      notificationDigestService.envoyerPushDifferes();
      return;
    }
    notificationDigestService.executerDigest(
        frequence instanceof String valeur ? valeur : "quotidien");
    if (avertissementCanalPerdu != null) {
      avertissementCanalPerdu.verifier();
    }
  }
}
