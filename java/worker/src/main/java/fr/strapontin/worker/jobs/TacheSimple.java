/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz générique : exécute la tâche du registre {@link TachesPlanifiees}
 * dont le nom est l'identité du {@code JobDetail}.
 * @since 3.1.4
 * @author Kwaite
 */
public class TacheSimple extends QuartzJobBean {

  private TachesPlanifiees taches;

  /** Constructeur par défaut, instancié par Spring. */
  public TacheSimple() {
    super();
  }

  /**
   * @param taches registre des tâches, injecté par Spring
   */
  @Autowired
  public void setTaches(final TachesPlanifiees taches) {
    this.taches = taches;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    taches.executer(context.getJobDetail().getKey().getName());
  }
}
