/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerPurgeService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : suppression des entrées de `logs_admin` plus anciennes que 30 jours,
/// tous les jours.
///
/// @since 2.2.4
/// @author Kwaite
public class PurgerLogsAdminJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(PurgerLogsAdminJob.class);

  private WorkerPurgeService workerPurgeService;

  @Autowired
  public void setWorkerPurgeService(WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerPurgeService.executerPurgerLogsAdmin();
    } catch (Exception e) {
      log.error("purger:logs-admin échoué", e);
      throw e;
    }
  }
}
