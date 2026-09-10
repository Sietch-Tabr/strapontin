/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.worker.WorkerFetchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

class FetchArtistesBatchJobTest {

  @Test
  void executeInternal_sansRetry_delegueAuCycleComplet() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchArtistesBatchJob job = new FetchArtistesBatchJob();
    job.setWorkerFetchService(workerFetchService);

    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(workerFetchService).executerFetchArtistesBatch();
  }

  @Test
  void executeInternal_avecIdsRetry_delegueAuCycleCible() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchArtistesBatchJob job = new FetchArtistesBatchJob();
    job.setWorkerFetchService(workerFetchService);

    JobDataMap donnees = new JobDataMap();
    donnees.put(FetchArtistesBatchJob.CLE_IDS_RETRY, "artiste-1,artiste-2");
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService).executerFetchArtistesBatch(List.of("artiste-1", "artiste-2"));
  }
}
