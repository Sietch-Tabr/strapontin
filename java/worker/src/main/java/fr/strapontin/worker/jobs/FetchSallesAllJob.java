/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
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

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    workerService.executerFetchSallesAll();
  }
}
