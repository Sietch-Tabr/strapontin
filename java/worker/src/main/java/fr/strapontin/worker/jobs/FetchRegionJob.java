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
 * Job Quartz : fetch par région pour tous les départements configurés, toutes les 2 heures.
 *
 * <p>Un retry ciblé ({@code RetryLotsService}) transmet via la {@code JobDataMap} du trigger la
 * liste (virgule-séparée) des départements en échec du cycle précédent, au format {@link
 * #CLE_IDS_RETRY} — sinon le cycle complet s'exécute normalement.
 *
 * @since 2.0.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchRegionJob extends QuartzJobBean {

  public static final String CLE_IDS_RETRY = "deptsRetry";

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(final WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    final String idsRetry = context.getMergedJobDataMap().getString(CLE_IDS_RETRY);
    if (idsRetry == null || idsRetry.isBlank()) {
      workerFetchService.executerFetchRegion();
      return;
    }
    final List<String> depts = Arrays.asList(idsRetry.split(","));
    workerFetchService.executerFetchRegion(depts);
  }
}
