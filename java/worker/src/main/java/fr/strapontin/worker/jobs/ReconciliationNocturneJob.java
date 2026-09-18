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
 * Job Quartz : réconciliation nocturne des sources salles/
 * artistes, cron quotidien 3h (détail : CLAUDE.md, Jobs
 * background).
 * @since 2.6.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class ReconciliationNocturneJob extends QuartzJobBean {

  private WorkerService workerService;

  /** Constructeur par défaut, instancié par Spring. */
  public ReconciliationNocturneJob() {
    super();
  }

  /**
   * @param workerService service d'orchestration injecté par Spring
   */
  @Autowired
  public void setWorkerService(final WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerService.executerReconciliationNocturne();
  }
}
