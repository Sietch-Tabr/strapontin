/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import fr.strapontin.worker.maintenance.WorkerStatsService;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Exécution du job {@code notifier:version} : la version courante de
 * l'application, ou celle transmise par l'admin, est annoncée et l'exécution
 * enregistrée dans les statistiques des jobs.
 * @since 3.0.6
 * @author Kwaite
 */
@Service
public class ExecutionNotificationVersion {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionNotificationVersion.class);

  private final NotificationVersionPushService notificationVersionPushService;
  private final NotificationVersionEmailService notificationVersionEmailService;
  private final WorkerStatsService workerStatsService;
  private final BuildProperties buildProperties;

  /**
   * @param notificationVersionPushService envoi de l'annonce aux abonnés
   * @param notificationVersionEmailService annonce par email, pour qui n'a
   *     aucun appareil push
   * @param workerStatsService enregistrement de l'exécution
   * @param buildProperties version courante de l'application
   */
  public ExecutionNotificationVersion(
      final NotificationVersionPushService notificationVersionPushService,
      final NotificationVersionEmailService notificationVersionEmailService,
      final WorkerStatsService workerStatsService,
      final BuildProperties buildProperties) {
    this.notificationVersionPushService = notificationVersionPushService;
    this.notificationVersionEmailService = notificationVersionEmailService;
    this.workerStatsService = workerStatsService;
    this.buildProperties = buildProperties;
  }

  /**
   * @param versionTransmise version à annoncer, ou {@code null} ou vide pour la
   *     version courante
   */
  public void executer(final String versionTransmise) {
    final String version =
        versionTransmise == null || versionTransmise.isBlank()
            ? buildProperties.getVersion()
            : versionTransmise;
    workerStatsService.executerAvecStats(
        LOG,
        "notifier:version",
        () ->
            new ResultatPersistance(
                notificationVersionPushService.notifierVersion(version)
                    + notificationVersionEmailService.notifierVersion(version),
                0));
    LOG.info("notifier:version terminé version={}", version);
  }
}
