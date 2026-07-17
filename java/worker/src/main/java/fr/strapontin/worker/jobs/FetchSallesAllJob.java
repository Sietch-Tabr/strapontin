/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerFetchService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : fetch périodique de toutes les salles ayant des sources configurées.
///
/// <p>Alimente le même pipeline que [FetchSalleJob] mais boucle sur toutes les salles
/// de {@code salle_sources} plutôt que d'attendre un déclenchement manuel.
/// Exécuté au même rythme adaptatif que {@code fetch:region}.
///
/// @since 2.4.0
/// @author Kwaite
@DisallowConcurrentExecution
public class FetchSallesAllJob extends QuartzJobBean {

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    workerFetchService.executerFetchSallesAll();
  }
}
