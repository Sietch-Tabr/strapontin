/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerPurgeService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : suppression des concerts passés depuis plus de 7 jours, et des concerts futurs
/// jamais reconfirmés depuis très longtemps, tous les jours à minuit.
///
/// @since 2.0.0
/// @author Kwaite
public class PurgerConcertsJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  @Autowired
  public void setWorkerPurgeService(WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    workerPurgeService.executerPurgerConcerts();
    workerPurgeService.executerPurgerConcertsNonConfirmes();
  }
}
