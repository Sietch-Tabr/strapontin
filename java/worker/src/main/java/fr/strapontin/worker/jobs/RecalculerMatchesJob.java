/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : recalcul des correspondances utilisateurs, toutes les 2 heures.
///
/// @since 2.0.0
/// @author Kwaite
public class RecalculerMatchesJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(RecalculerMatchesJob.class);

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerRecalculerMatches(null);
    } catch (Exception e) {
      log.error("recalculer:matches échoué", e);
      throw e;
    }
  }
}
