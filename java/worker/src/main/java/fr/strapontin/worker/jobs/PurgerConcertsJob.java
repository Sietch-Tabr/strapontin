/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerPurgePerimetreService;
import fr.strapontin.worker.WorkerPurgeService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz de minuit : concerts passés, jamais reconfirmés, hors de portée de
 * fetch ou hors du périmètre de tout utilisateur.
 *
 * @since 2.0.0
 * @author Kwaite
 */
public class PurgerConcertsJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;
  private WorkerPurgePerimetreService workerPurgePerimetreService;

  /** Constructeur par défaut, instancié par Spring. */
  public PurgerConcertsJob() {
    super();
  }

  /**
   * @param workerPurgeService service de purge injecté par Spring
   */
  @Autowired
  public void setWorkerPurgeService(final WorkerPurgeService workerPurgeService) {
    this.workerPurgeService = workerPurgeService;
  }

  /**
   * @param workerPurgePerimetreService purge hors périmètre injectée par Spring
   */
  @Autowired
  public void setWorkerPurgePerimetreService(
      final WorkerPurgePerimetreService workerPurgePerimetreService) {
    this.workerPurgePerimetreService = workerPurgePerimetreService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerPurgeService.executerPurgerConcerts();
    workerPurgeService.executerPurgerConcertsNonConfirmes();
    workerPurgeService.executerPurgerConcertsHorsPortee();
    workerPurgePerimetreService.executerPurgerConcertsHorsPerimetre();
  }
}
