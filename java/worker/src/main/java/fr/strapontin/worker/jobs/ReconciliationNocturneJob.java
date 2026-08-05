/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : réconciliation nocturne des sources salles/artistes, tous les jours à 3h.
///
/// Health-check et redécouverte des identifiants TM suspects, puis enrichissement
/// Wikidata/OSM/Deezer. Durée variable selon les appels externes rate-limités —
/// {@code @DisallowConcurrentExecution} empêche tout chevauchement avec un déclenchement manuel.
///
/// @since 2.6.0
/// @author Kwaite
@DisallowConcurrentExecution
public class ReconciliationNocturneJob extends QuartzJobBean {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationNocturneJob.class);

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    try {
      workerService.executerReconciliationNocturne();
    } catch (Exception e) {
      log.error("reconciliation:nocturne échoué", e);
      throw e;
    }
  }
}
