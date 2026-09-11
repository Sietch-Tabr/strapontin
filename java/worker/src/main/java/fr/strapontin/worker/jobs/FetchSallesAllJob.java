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
 * Job Quartz : fetch périodique de toutes les salles ayant des sources configurées.
 *
 * <p>Alimente le même pipeline que [FetchSalleJob] mais boucle sur toutes les salles de {@code
 * salle_sources} plutôt que d'attendre un déclenchement manuel. Exécuté au même rythme adaptatif
 * que {@code fetch:region}.
 *
 * <p>Un retry ciblé ({@code RetryLotsService}) transmet via la {@code JobDataMap} du trigger la
 * liste (virgule-séparée) des {@code salles.id} en échec du cycle précédent, au format {@link
 * #CLE_IDS_RETRY} — sinon le cycle complet s'exécute normalement.
 *
 * @since 2.4.0
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class FetchSallesAllJob extends QuartzJobBean {

  public static final String CLE_IDS_RETRY = "salleIdsRetry";

  private WorkerFetchService workerFetchService;

  @Autowired
  public void setWorkerFetchService(WorkerFetchService workerFetchService) {
    this.workerFetchService = workerFetchService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    String idsRetry = context.getMergedJobDataMap().getString(CLE_IDS_RETRY);
    if (idsRetry == null || idsRetry.isBlank()) {
      workerFetchService.executerFetchSallesAll();
      return;
    }
    List<String> ids = Arrays.asList(idsRetry.split(","));
    workerFetchService.executerFetchSallesAll(ids);
  }
}
