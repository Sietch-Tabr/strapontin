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

class FetchSallesAllJobTest {

  @Test
  void executeInternal_sansRetry_delegueAuCycleComplet() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchSallesAllJob job = new FetchSallesAllJob();
    job.setWorkerFetchService(workerFetchService);

    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

    job.executeInternal(context);

    verify(workerFetchService).executerFetchSallesAll();
  }

  @Test
  void executeInternal_avecIdsRetry_delegueAuCycleCible() {
    WorkerFetchService workerFetchService = mock(WorkerFetchService.class);
    FetchSallesAllJob job = new FetchSallesAllJob();
    job.setWorkerFetchService(workerFetchService);

    JobDataMap donnees = new JobDataMap();
    donnees.put(FetchSallesAllJob.CLE_IDS_RETRY, "salle-1,salle-2");
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(donnees);

    job.executeInternal(context);

    verify(workerFetchService).executerFetchSallesAll(List.of("salle-1", "salle-2"));
  }
}
