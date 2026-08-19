/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.WorkerPurgeService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class PurgerSallesJobTest {

  @Test
  void executeInternal_delegueAWorkerPurgeService() {
    WorkerPurgeService workerPurgeService = mock(WorkerPurgeService.class);
    PurgerSallesJob job = new PurgerSallesJob();
    job.setWorkerPurgeService(workerPurgeService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(workerPurgeService).executerPurgerSalles();
  }
}
