/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.NotificationVersionPushService;
import fr.strapontin.worker.WorkerStatsService;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.boot.info.BuildProperties;

class NotifierVersionJobTest {

  private NotificationVersionPushService service;
  private NotifierVersionJob job;

  @BeforeEach
  void setUp() {
    service = mock(NotificationVersionPushService.class);
    final WorkerStatsService stats = mock(WorkerStatsService.class);
    // Le suivi des statistiques exécute le travail transmis, comme en production.
    doAnswer(
            invocation -> {
              final Supplier<ResultatPersistance> travail = invocation.getArgument(2);
              travail.get();
              return null;
            })
        .when(stats)
        .executerAvecStats(any(), eq("notifier:version"), any());
    final Properties proprietes = new Properties();
    proprietes.setProperty("version", "9.9.9");
    job = new NotifierVersionJob();
    job.setNotificationVersionPushService(service);
    job.setWorkerStatsService(stats);
    job.setBuildProperties(new BuildProperties(proprietes));
  }

  private JobExecutionContext contexte(final JobDataMap donnees) {
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);
    return context;
  }

  @Test
  void executeInternal_versionTransmise_laNotifie() {
    final JobDataMap donnees = new JobDataMap();
    donnees.put(NotifierVersionJob.CLE_VERSION, "3.0.0");

    job.executeInternal(contexte(donnees));

    verify(service).notifierVersion("3.0.0");
  }

  @Test
  void executeInternal_versionAbsente_notifieLaVersionCourante() {
    job.executeInternal(contexte(new JobDataMap()));

    verify(service).notifierVersion("9.9.9");
  }

  @Test
  void executeInternal_versionBlanche_notifieLaVersionCourante() {
    final JobDataMap donnees = new JobDataMap();
    donnees.put(NotifierVersionJob.CLE_VERSION, "  ");

    job.executeInternal(contexte(donnees));

    verify(service).notifierVersion("9.9.9");
  }
}
