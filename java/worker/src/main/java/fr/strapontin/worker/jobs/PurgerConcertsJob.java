/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : suppression des concerts passés depuis plus de 7 jours, tous les jours à minuit.
///
/// @since 2.0.0
/// @author Kwaite
public class PurgerConcertsJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(PurgerConcertsJob.class);

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerPurgerConcerts();
    } catch (Exception e) {
      log.error("purger:concerts échoué", e);
      throw e;
    }
  }
}
