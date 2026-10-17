/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.NotificationVersionPushService;
import fr.strapontin.worker.WorkerStatsService;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : envoi push "nouvelle version disponible" — chaque jour à 11h
 * (version courante de l'application, une seule annonce par version et par
 * utilisateur) ou sur demande depuis l'admin avec la version transmise.
 * @since 3.0.0
 * @author Kwaite
 */
public class NotifierVersionJob extends QuartzJobBean {

  public static final String CLE_VERSION = "version";

  private static final Logger LOG = LoggerFactory.getLogger(NotifierVersionJob.class);

  private NotificationVersionPushService notificationVersionPushService;
  private WorkerStatsService workerStatsService;
  private BuildProperties buildProperties;

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

  /**
   * @param workerStatsService enregistrement de l'exécution du job
   */
  @Autowired
  public void setWorkerStatsService(final WorkerStatsService workerStatsService) {
    this.workerStatsService = workerStatsService;
  }

  /**
   * @param buildProperties version courante, utilisée sans version transmise
   */
  @Autowired
  public void setBuildProperties(final BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    final String transmise = context.getMergedJobDataMap().getString(CLE_VERSION);
    final String version =
        transmise == null || transmise.isBlank() ? buildProperties.getVersion() : transmise;
    workerStatsService.executerAvecStats(
        LOG,
        "notifier:version",
        () -> new ResultatPersistance(notificationVersionPushService.notifierVersion(version), 0));
    LOG.info("notifier:version terminé version={}", version);
  }
}
