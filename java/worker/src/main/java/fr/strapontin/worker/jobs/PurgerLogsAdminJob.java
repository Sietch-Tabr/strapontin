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
 * Job Quartz : suppression des entrées de {@code logs_admin} plus anciennes que 30 jours, tous les
 * jours.
 *
 * @since 2.2.4
 * @author Kwaite
 */
public class PurgerLogsAdminJob extends QuartzJobBean {

  private WorkerPurgeService workerPurgeService;

  /** Constructeur par défaut, instancié par Spring. */
  public PurgerLogsAdminJob() {
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
    workerPurgeService.executerPurgerLogsAdmin();
  }
}
