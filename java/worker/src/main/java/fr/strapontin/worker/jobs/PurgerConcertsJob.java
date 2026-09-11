/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerPurgeService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : suppression des concerts passés depuis plus de 7 jours, des concerts futurs jamais
 * reconfirmés depuis très longtemps, et des concerts hors de toute portée de fetch, tous les jours
 * à minuit.
 *
 * @since 2.0.0
 * @author Kwaite
 */
public class PurgerConcertsJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  @Autowired
  public void setWorkerPurgeService(final WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerPurgeService.executerPurgerConcerts();
    workerPurgeService.executerPurgerConcertsNonConfirmes();
    workerPurgeService.executerPurgerConcertsHorsPortee();
  }
}
