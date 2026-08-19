/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerFetchService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : fetch global de tous les artistes suivis toutes les 2 heures.
///
/// @since 2.0.0
/// @author Kwaite
@DisallowConcurrentExecution
public class FetchArtistesBatchJob extends QuartzJobBean {

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    workerFetchService.executerFetchArtistesBatch();
  }
}
