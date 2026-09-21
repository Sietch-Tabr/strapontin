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
 * Job Quartz : relance les comptes invités jamais activés puis supprime
 * ceux dont l'invitation a expiré sans activation, tous les jours.
 *
 * @since 3.0.0
 * @author Kwaite
 */
public class GererInvitesExpiresJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  /** Constructeur par défaut, instancié par Spring. */
  public GererInvitesExpiresJob() {
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
    workerPurgeService.executerGererInvitesExpires();
  }
}
