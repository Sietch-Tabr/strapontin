/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.WorkerFetchService;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class FetchSalleJobTest {

  @Test
  void executeInternal_identifiantsPresents_delegueAWorkerFetchService() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    JobDataMap donnees = new JobDataMap();
    donnees.put(FetchSalleJob.CLE_IDENTIFIANTS, "tm:rZFSnyZdFvA");
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService).executerFetchSalle("tm:rZFSnyZdFvA");
  }

  @Test
  void executeInternal_identifiantsAbsents_neDelegueRien() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(workerFetchService, never()).executerFetchSalle(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeInternal_identifiantsVides_neDelegueRien() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchSalleJob job = new FetchSalleJob();
    job.setWorkerFetchService(workerFetchService);

    JobDataMap donnees = new JobDataMap();
    donnees.put(FetchSalleJob.CLE_IDENTIFIANTS, "   ");
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService, never()).executerFetchSalle(org.mockito.ArgumentMatchers.any());
  }
}
