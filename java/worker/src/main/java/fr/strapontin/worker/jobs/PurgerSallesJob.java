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

/// Job Quartz : suppression des salles orphelines (sans concerts futurs, sans utilisateurs et
/// sans slug Digitick configuré), tous les jours à 0h05.
///
/// S'exécute après {@link PurgerConcertsJob} pour travailler sur un jeu de données cohérent.
///
/// @since 2.3.2
/// @author Kwaite
public class PurgerSallesJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(PurgerSallesJob.class);

  private WorkerPurgeService workerPurgeService;

  @Autowired
  public void setWorkerPurgeService(WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerPurgeService.executerPurgerSalles();
    } catch (Exception e) {
      log.error("purger:salles échoué", e);
      throw e;
    }
  }
}
