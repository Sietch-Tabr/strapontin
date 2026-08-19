/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.worker.WorkerRevocationService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

class RevoquerAdminsJobTest {

  @Test
  void executeInternal_delegueAWorkerRevocationService() {
    WorkerRevocationService workerRevocationService = mock(WorkerRevocationService.class);
    RevoquerAdminsJob job = new RevoquerAdminsJob();
    job.setWorkerRevocationService(workerRevocationService);

    job.executeInternal(mock(JobExecutionContext.class));

    verify(workerRevocationService).executerRevoquerAdmins();
  }
}
