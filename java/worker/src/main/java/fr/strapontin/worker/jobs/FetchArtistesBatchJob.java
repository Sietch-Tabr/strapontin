/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : fetch global de tous les artistes suivis toutes les 2 heures.
///
/// @since 2.0.0
/// @author Kwaite
public class FetchArtistesBatchJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(FetchArtistesBatchJob.class);

  @Autowired private WorkerService workerService;

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerFetchArtistesBatch();
    } catch (Exception e) {
      log.error("fetch:artistes:batch échoué", e);
      throw e;
    }
  }
}
