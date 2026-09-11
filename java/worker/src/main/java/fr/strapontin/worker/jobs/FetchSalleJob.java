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
 * Job Quartz : fetch d'une salle par ses identifiants préfixés, toutes les 2 heures.
 *
 * <p>Les identifiants sont transmis via la {@code JobDataMap} lors de la planification, au format
 * virgule-séparé : {@code "tm:rZFSnyZdFvA"} ou {@code "tm:id1,digitick:slug"}.
 *
 * @since 2.0.0
 * @author Kwaite
 */
public class FetchSalleJob extends QuartzJobBean {

  public static final String CLE_IDENTIFIANTS = "identifiants";

  private static final Logger log = LoggerFactory.getLogger(FetchSalleJob.class);

  private WorkerFetchService workerFetchService;

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
      log.warn("fetch:salle — identifiants absents dans la JobDataMap");
      return;
    }
    try {
      workerFetchService.executerFetchSalle(identifiants);
    } catch (Exception e) {
      log.error("fetch:salle échoué identifiants={}", identifiants, e);
      throw e;
    }
  }
}
