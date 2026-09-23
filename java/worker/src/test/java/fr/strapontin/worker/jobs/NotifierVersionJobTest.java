/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.NotificationVersionPushService;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class NotifierVersionJobTest {

  @Test
  void executeInternal_versionPresente_delegueAuService() {
    final NotificationVersionPushService service = mock(NotificationVersionPushService.class);
    final NotifierVersionJob job = new NotifierVersionJob();
    job.setNotificationVersionPushService(service);

    final JobDataMap donnees = new JobDataMap();
    donnees.put(NotifierVersionJob.CLE_VERSION, "3.0.0");
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(service).notifierVersion("3.0.0");
  }

  @Test
  void executeInternal_versionAbsente_neDelegueRien() {
    final NotificationVersionPushService service = mock(NotificationVersionPushService.class);
    final NotifierVersionJob job = new NotifierVersionJob();
    job.setNotificationVersionPushService(service);

    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(service, never()).notifierVersion(org.mockito.ArgumentMatchers.any());
  }
}
