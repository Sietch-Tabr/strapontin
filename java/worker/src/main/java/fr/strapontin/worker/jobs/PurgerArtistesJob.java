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

/// Job Quartz : suppression des artistes orphelins (sans concerts futurs ni utilisateurs),
/// tous les jours à 0h06.
///
/// S'exécute après {@link PurgerSallesJob} pour éviter de laisser des salles fantômes
/// référencées par des concerts orphelins déjà supprimés.
///
/// @since 2.3.2
/// @author Kwaite
public class PurgerArtistesJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(PurgerArtistesJob.class);

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerPurgerArtistes();
    } catch (Exception e) {
      log.error("purger:artistes échoué", e);
      throw e;
    }
  }
}
