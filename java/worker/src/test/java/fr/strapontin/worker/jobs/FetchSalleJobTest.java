/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.fetch.WorkerFetchService;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class FetchSalleJobTest {

  @Test
  void executeInternal_identifiantsPresents_delegueAWorkerFetchService() {
    final WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    final FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    final JobDataMap donnees = new JobDataMap();
    donnees.put(FetchSalleJob.CLE_IDENTIFIANTS, "tm:rZFSnyZdFvA");
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService).executerFetchSalle("tm:rZFSnyZdFvA");
  }

  @Test
  void executeInternal_identifiantsAbsents_neDelegueRien() {
    final WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    final FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(workerFetchService, never()).executerFetchSalle(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeInternal_identifiantsVides_neDelegueRien() {
    final WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    final FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    final JobDataMap donnees = new JobDataMap();
    donnees.put(FetchSalleJob.CLE_IDENTIFIANTS, "   ");
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService, never()).executerFetchSalle(org.mockito.ArgumentMatchers.any());
  }
}
