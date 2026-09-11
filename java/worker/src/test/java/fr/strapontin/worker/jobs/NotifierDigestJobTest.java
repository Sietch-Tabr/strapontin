/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.NotificationDigestService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class NotifierDigestJobTest {

  @Test
  void executeInternal_delegueANotificationDigestServiceAvecCycleQuotidien() {
    final NotificationDigestService notificationDigestService = mock(NotificationDigestService.class);
    final NotifierDigestJob job = new NotifierDigestJob();
    job.setNotificationDigestService(notificationDigestService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(notificationDigestService).executerDigest("quotidien");
  }
}
