/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerFetchService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : fetch par région pour tous les départements configurés, toutes les 2 heures.
///
/// @since 2.0.0
/// @author Kwaite
@DisallowConcurrentExecution
public class FetchRegionJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(FetchRegionJob.class);

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerFetchService.executerFetchRegion();
    } catch (Exception e) {
      log.error("fetch:region échoué", e);
      throw e;
    }
  }
}
