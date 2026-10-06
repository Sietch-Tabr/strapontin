/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.NotificationDigestService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class NotifierDigestJobTest {

  @Test
  void executeInternal_delegueANotificationDigestServiceAvecCycleQuotidien() {
    final NotificationDigestService notificationDigestService =
        mock(NotificationDigestService.class);
    final NotifierDigestJob job = new NotifierDigestJob();
    job.setNotificationDigestService(notificationDigestService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(notificationDigestService).executerDigest("quotidien");
  }

  @Test
  void executeInternal_declencheurHebdomadaire_delegueAvecCycleHebdomadaire() {
    final NotificationDigestService notificationDigestService =
        mock(NotificationDigestService.class);
    final NotifierDigestJob job = new NotifierDigestJob();
    job.setNotificationDigestService(notificationDigestService);
    final JobExecutionContext contexte = mock(JobExecutionContext.class);
    when(contexte.getMergedJobDataMap())
        .thenReturn(new JobDataMap(Map.of(NotifierDigestJob.CLE_FREQUENCE, "hebdomadaire")));

    job.executeInternal(contexte);

    verify(notificationDigestService).executerDigest("hebdomadaire");
  }
}
