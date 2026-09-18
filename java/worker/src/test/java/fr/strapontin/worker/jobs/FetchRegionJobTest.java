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

class FetchRegionJobTest {

  @Test
  void executeInternal_sansRetry_delegueAuCycleComplet() {
    final WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    final FetchRegionJob job = new FetchRegionJob();
    job.setWorkerFetchService(workerFetchService);

    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(workerFetchService).executerFetchRegion();
  }

  @Test
  void executeInternal_avecIdsRetry_delegueAuCycleCible() {
    final WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    final FetchRegionJob job = new FetchRegionJob();
    job.setWorkerFetchService(workerFetchService);

    final JobDataMap donnees = new JobDataMap();
    donnees.put(FetchRegionJob.CLE_IDS_RETRY, "75,92");
    final JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService).executerFetchRegion(List.of("75", "92"));
  }
}
