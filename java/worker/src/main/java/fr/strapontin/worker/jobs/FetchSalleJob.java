/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.WorkerService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/// Job Quartz : fetch d'une salle par son venueId Ticketmaster, toutes les 2 heures.
///
/// Le `venueId` est transmis via la `JobDataMap` lors de la planification.
///
/// @since 2.0.0
/// @author Kwaite
public class FetchSalleJob extends QuartzJobBean {

  public static final String CLE_VENUE_ID = "venueId";

  private static final Logger log = LoggerFactory.getLogger(FetchSalleJob.class);

  private WorkerService workerService;

  @Autowired
  public void setWorkerService(WorkerService workerService) {
    this.workerService = workerService;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    String venueId = context.getMergedJobDataMap().getString(CLE_VENUE_ID);
    if (venueId == null || venueId.isBlank()) {
      log.warn("fetch:salle — venueId absent dans la JobDataMap");
      return;
    }
    try {
      workerService.executerFetchSalle(venueId);
    } catch (Exception e) {
      log.error("fetch:salle échoué venueId={}", venueId, e);
      throw e;
    }
  }
}
