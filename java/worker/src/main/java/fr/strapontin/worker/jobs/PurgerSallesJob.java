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
 * Job Quartz : suppression des salles orphelines (sans concerts futurs, sans utilisateurs et sans
 * slug Digitick configuré), tous les jours à 0h05.
 *
 * <p>S'exécute après {@link PurgerConcertsJob} pour travailler sur un jeu de données cohérent.
 *
 * @since 2.3.2
 * @author Kwaite
 */
public class PurgerSallesJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  /** Constructeur par défaut, instancié par Spring. */
  public PurgerSallesJob() {
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
    workerPurgeService.executerPurgerSalles();
  }
}
