/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.WorkerService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class RecalculerMatchesJobTest {

  @Test
  void executeInternal_delegueAWorkerServicePourTousLesUsers() {
    WorkerService workerService = mock(WorkerService.class);
    RecalculerMatchesJob job = new RecalculerMatchesJob();
    job.setWorkerService(workerService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(workerService).executerRecalculerMatches(null);
  }
}
