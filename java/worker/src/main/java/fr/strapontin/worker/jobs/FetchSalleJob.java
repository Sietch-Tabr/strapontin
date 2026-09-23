/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerFetchService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : fetch d'une salle par ses identifiants préfixés,
 * déclenché à la demande (ajout salle, API — détail : CLAUDE.md,
 * Jobs background).
 * @since 2.0.0
 * @author Kwaite
 */
public class FetchSalleJob extends QuartzJobBean {

  public static final String CLE_IDENTIFIANTS = "identifiants";

  private static final Logger LOG = LoggerFactory.getLogger(FetchSalleJob.class);

  private WorkerFetchService workerFetchService;

  /** Constructeur par défaut, instancié par Spring. */
  public FetchSalleJob() {
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
    final String identifiants = context.getMergedJobDataMap().getString(CLE_IDENTIFIANTS);
    if (identifiants == null || identifiants.isBlank()) {
      LOG.warn("fetch:salle — identifiants absents dans la JobDataMap");
      return;
    }
    try {
      workerFetchService.executerFetchSalle(identifiants);
    } catch (Exception e) {
      LOG.error("fetch:salle échoué identifiants={} : {}", identifiants, e.getMessage());
      throw e;
    }
  }
}
