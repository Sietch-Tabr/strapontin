/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.fetch.WorkerFetchService;
import java.util.Arrays;
import java.util.List;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : fetch périodique de toutes les salles avec
 * sources configurées (détail : CLAUDE.md, Jobs background).
 * @since 2.4.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchSallesAllJob extends QuartzJobBean {

  public static final String CLE_IDS_RETRY = "salleIdsRetry";

  private WorkerFetchService workerFetchService;

  /** Constructeur par défaut, instancié par Spring. */
  public FetchSallesAllJob() {
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
    final String idsRetry = context.getMergedJobDataMap().getString(CLE_IDS_RETRY);
    if (idsRetry == null || idsRetry.isBlank()) {
      workerFetchService.executerFetchSallesAll();
      return;
    }
    final List<String> ids = Arrays.asList(idsRetry.split(","));
    workerFetchService.executerFetchSallesAll(ids);
  }
}
