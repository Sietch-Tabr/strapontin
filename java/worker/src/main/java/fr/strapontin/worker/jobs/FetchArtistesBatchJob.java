/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerFetchService;
import java.util.Arrays;
import java.util.List;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : fetch global de tous les artistes suivis toutes les 2 heures.
 *
 * <p>Un retry ciblé ({@code RetryLotsService}) transmet via la {@code JobDataMap} du trigger la
 * liste (virgule-séparée) des {@code artistes.id} en échec du cycle précédent, au format {@link
 * #CLE_IDS_RETRY} — sinon le cycle complet s'exécute normalement.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchArtistesBatchJob extends QuartzJobBean {

  public static final String CLE_IDS_RETRY = "artisteIdsRetry";

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    String idsRetry = context.getMergedJobDataMap().getString(CLE_IDS_RETRY);
    if (idsRetry == null || idsRetry.isBlank()) {
      workerFetchService.executerFetchArtistesBatch();
      return;
    }
    List<String> ids = Arrays.asList(idsRetry.split(","));
    workerFetchService.executerFetchArtistesBatch(ids);
  }
}
