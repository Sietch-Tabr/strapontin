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
 * Job Quartz : suppression des artistes orphelins (sans concerts futurs ni utilisateurs), tous les
 * jours à 0h06.
 *
 * <p>S'exécute après {@link PurgerSallesJob} pour éviter de laisser des salles fantômes référencées
 * par des concerts orphelins déjà supprimés.
 *
 * @since 2.3.2
 * @author Kwaite
 */
public class PurgerArtistesJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  public PurgerArtistesJob() {
    super();
  }

  /**
   * @param workerPurgeService service de purge injecté par Spring
   */
  @Autowired
  public void setWorkerPurgeService(final WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerPurgeService.executerPurgerArtistes();
  }
}
