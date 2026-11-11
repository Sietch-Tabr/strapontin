/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.jobs;

import org.quartz.DisallowConcurrentExecution;

/**
 * Comme {@link TacheSimple}, avec l'exclusion de concurrence : deux exécutions
 * de la même tâche ne se chevauchent jamais.
 * @since 3.1.4
 * @author Kwaite
 */
@DisallowConcurrentExecution
public class TacheExclusive extends TacheSimple {

  /** Constructeur par défaut, instancié par Spring. */
  public TacheExclusive() {
    super();
  }
}
