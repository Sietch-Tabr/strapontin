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
 * Job Quartz : fetch global de tous les artistes suivis, cron
 * adaptatif (détail : CLAUDE.md, Jobs background).
 * @since 2.0.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchArtistesBatchJob extends QuartzJobBean {

  public static final String CLE_IDS_RETRY = "artisteIdsRetry";

  private WorkerFetchService workerFetchService;

  /** Constructeur par défaut, instancié par Spring. */
  public FetchArtistesBatchJob() {
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
      workerFetchService.executerFetchArtistesBatch();
      return;
    }
    final List<String> ids = Arrays.asList(idsRetry.split(","));
    workerFetchService.executerFetchArtistesBatch(ids);
  }
}
