/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerSuiviCyclesService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : détection des types de job planifiés silencieusement en retard, toutes les heures.
 *
 * @since 2.8.3
 * @author Kwaite
 */
public class SuiviCyclesJobsJob extends QuartzJobBean {

  private WorkerSuiviCyclesService workerSuiviCyclesService;

  public SuiviCyclesJobsJob() {}

  /**
   * @param workerSuiviCyclesService service de suivi de cycles injecté par Spring
   */
  @Autowired
  public void setWorkerSuiviCyclesService(final WorkerSuiviCyclesService workerSuiviCyclesService) {
    this.workerSuiviCyclesService = workerSuiviCyclesService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerSuiviCyclesService.executerSuiviCycles();
  }
}
