/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.ExecutionNotificationVersion;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class NotifierVersionJobTest {

  private static JobExecutionContext contexte(final JobDataMap donnees) {
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);
    return context;
  }

  @Test
  void executeInternal_versionTransmise_laPasseALExecution() {
    final ExecutionNotificationVersion execution = mock(ExecutionNotificationVersion.class);
    final NotifierVersionJob job = new NotifierVersionJob();
    job.setExecution(execution);
    final JobDataMap donnees = new JobDataMap();
    donnees.put(NotifierVersionJob.CLE_VERSION, "3.0.0");

    job.executeInternal(contexte(donnees));

    verify(execution).executer("3.0.0");
  }

  @Test
  void executeInternal_versionAbsente_transmetNull() {
    final ExecutionNotificationVersion execution = mock(ExecutionNotificationVersion.class);
    final NotifierVersionJob job = new NotifierVersionJob();
    job.setExecution(execution);

    job.executeInternal(contexte(new JobDataMap()));

    verify(execution).executer(null);
  }
}
