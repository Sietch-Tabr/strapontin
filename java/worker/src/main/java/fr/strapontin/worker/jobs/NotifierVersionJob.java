/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import fr.strapontin.worker.ExecutionNotificationVersion;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Job Quartz : envoi push "nouvelle version disponible" — chaque jour à 11h
 * (version courante de l'application, une seule annonce par version et par
 * utilisateur) ou sur demande depuis l'admin avec la version transmise.
 * @since 3.0.0
 * @author Kwaite
 */
public class NotifierVersionJob extends QuartzJobBean {

  /** Clé des données du déclencheur portant la version à annoncer. */
  public static final String CLE_VERSION = "version";

  private ExecutionNotificationVersion execution;

  /** Constructeur par défaut, instancié par Spring. */
  public NotifierVersionJob() {
    super();
  }

  /**
   * @param execution exécution du job, injectée par Spring
   */
  @Autowired
  public void setExecution(final ExecutionNotificationVersion execution) {
    this.execution = execution;
  }

  @Override
  protected void executeInternal(final JobExecutionContext context) {
    execution.executer(context.getMergedJobDataMap().getString(CLE_VERSION));
  }
}
