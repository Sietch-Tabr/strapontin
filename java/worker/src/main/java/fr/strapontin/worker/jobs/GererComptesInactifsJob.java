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
 * Job Quartz : relance les comptes inactifs depuis 6 mois puis supprime
 * ceux dont le délai de grâce est dépassé, tous les jours.
 *
 * @since 3.0.0
 * @author Kwaite
 */
public class GererComptesInactifsJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  /** Constructeur par défaut, instancié par Spring. */
  public GererComptesInactifsJob() {
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
    workerPurgeService.executerGererComptesInactifs();
  }
}
