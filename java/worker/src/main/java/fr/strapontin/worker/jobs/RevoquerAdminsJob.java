/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerRevocationService;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : révocation des admins dont le préavis de 7 jours est écoulé, chaque heure.
 *
 * @since 2.2.6
 * @author Kwaite
 */
public class RevoquerAdminsJob extends QuartzJobBean {

  private WorkerRevocationService workerRevocationService;

  public RevoquerAdminsJob() {}

  /**
   * @param workerRevocationService service de révocation injecté par Spring
   */
  @Autowired
  public void setWorkerRevocationService(final WorkerRevocationService workerRevocationService) {
    this.workerRevocationService = workerRevocationService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerRevocationService.executerRevoquerAdmins();
  }
}
