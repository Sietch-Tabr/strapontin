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

  @Autowired
  public void setWorkerRevocationService(WorkerRevocationService workerRevocationService) {
    this.workerRevocationService = workerRevocationService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    workerRevocationService.executerRevoquerAdmins();
  }
}
