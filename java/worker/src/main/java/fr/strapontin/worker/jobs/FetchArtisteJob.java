/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.fetch.WorkerFetchService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : fetch d'un seul artiste, déclenché à son ajout — le cycle
 * complet reste au cron (détail : CLAUDE.md, Jobs background).
 * @since 3.0.2
 * @author Kwaite
 */
public class FetchArtisteJob extends QuartzJobBean {

  public static final String CLE_ARTISTE_ID = "artisteId";

  private static final Logger LOG = LoggerFactory.getLogger(FetchArtisteJob.class);

  private WorkerFetchService workerFetchService;

  /** Constructeur par défaut, instancié par Spring. */
  public FetchArtisteJob() {
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
    final String artisteId = context.getMergedJobDataMap().getString(CLE_ARTISTE_ID);
    if (artisteId == null || artisteId.isBlank()) {
      LOG.warn("fetch:artiste — identifiant absent dans la JobDataMap");
      return;
    }
    try {
      workerFetchService.executerFetchArtiste(artisteId);
    } catch (Exception e) {
      LOG.error("fetch:artiste échoué artisteId={} : {}", artisteId, e.getMessage());
      throw e;
    }
  }
}
