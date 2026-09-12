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

/**
 * Job Quartz : réconciliation nocturne des sources salles/artistes, tous les jours à 3h.
 *
 * <p>Health-check et redécouverte des identifiants TM suspects, puis enrichissement
 * Wikidata/OSM/Deezer. Durée variable selon les appels externes rate-limités —
 * {@code @DisallowConcurrentExecution} empêche tout chevauchement avec un déclenchement manuel.
 *
 * @since 2.6.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class ReconciliationNocturneJob extends QuartzJobBean {

  private WorkerService workerService;

  public ReconciliationNocturneJob() {
    super();
  }

  /**
   * @param workerService service d'orchestration injecté par Spring
   */
  @Autowired
  public void setWorkerService(final WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerService.executerReconciliationNocturne();
  }
}
