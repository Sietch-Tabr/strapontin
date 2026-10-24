/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.WorkerPurgePerimetreService;
import fr.strapontin.worker.WorkerPurgeService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class PurgerConcertsJobTest {

  @Test
  void executeInternal_delegueAWorkerPurgeService() {
    final WorkerPurgeService workerPurgeService = mock(WorkerPurgeService.class);
    final PurgerConcertsJob job = new PurgerConcertsJob();
    final WorkerPurgePerimetreService perimetre = mock(WorkerPurgePerimetreService.class);
    job.setWorkerPurgeService(workerPurgeService);
    job.setWorkerPurgePerimetreService(perimetre);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(workerPurgeService).executerPurgerConcerts();
    verify(workerPurgeService).executerPurgerConcertsNonConfirmes();
    verify(workerPurgeService).executerPurgerConcertsHorsPortee();
    verify(perimetre).executerPurgerConcertsHorsPerimetre();
  }
}
