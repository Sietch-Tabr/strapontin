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

/**
 * Job Quartz : lecture quotidienne, en journée, des sites de salles qui
 * vendent hors Ticketmaster et Eventim.
 * @since 3.0.6
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchSitesSallesJob extends QuartzJobBean {

  private WorkerFetchService workerFetchService;

  /** Constructeur par défaut, instancié par Spring. */
  public FetchSitesSallesJob() {
    super();
  }

  /**
   * @param workerFetchService service de fetch injecté par Spring
   */
  @Autowired
  public void setWorkerFetchService(final WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    workerFetchService.executerFetchSitesSalles();
  }
}
