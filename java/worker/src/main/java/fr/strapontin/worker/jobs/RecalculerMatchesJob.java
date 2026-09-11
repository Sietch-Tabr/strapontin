/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : recalcul des correspondances utilisateurs, toutes les 2 heures.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class RecalculerMatchesJob extends QuartzJobBean {

  private WorkerService workerService;

  /**
   * @param workerService service d'orchestration injecté par Spring
   */
  @Autowired
  public void setWorkerService(final WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerService.executerRecalculerMatches(null);
  }
}
