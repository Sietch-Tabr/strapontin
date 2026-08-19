/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.WorkerFetchService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class FetchArtistesBatchJobTest {

  @Test
  void executeInternal_delegueAWorkerFetchService() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchArtistesBatchJob job = new FetchArtistesBatchJob();
    job.setWorkerFetchService(workerFetchService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(workerFetchService).executerFetchArtistesBatch();
  }
}
