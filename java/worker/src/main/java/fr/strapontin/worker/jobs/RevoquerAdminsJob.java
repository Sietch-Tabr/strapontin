/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerRevocationService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : révocation des admins dont le préavis de 7 jours est écoulé, chaque heure.
///
/// @since 2.2.6
/// @author Kwaite
public class RevoquerAdminsJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(RevoquerAdminsJob.class);

  private WorkerRevocationService workerRevocationService;

  @Autowired
  public void setWorkerRevocationService(WorkerRevocationService workerRevocationService) {
    this.workerRevocationService = workerRevocationService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerRevocationService.executerRevoquerAdmins();
    } catch (Exception e) {
      log.error("revoquer:admins échoué", e);
      throw e;
    }
  }
}
