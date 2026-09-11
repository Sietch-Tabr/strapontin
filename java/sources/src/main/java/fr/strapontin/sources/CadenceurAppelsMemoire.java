/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implémentation en mémoire de {@link CadenceurAppels} : verrou sur l'instance, coordonne les
 * appels d'un seul processus quel que soit le nombre de jobs Quartz en parallèle. Une instance par
 * source ayant son propre rythme de cadence.
 *
 * @since 2.7.0
 * @author Kwaite
 */
final class CadenceurAppelsMemoire implements CadenceurAppels {

  private final long delaiMinMs;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /**
   * @param delaiMinMs écart minimal en millisecondes entre deux appels successifs
   */
  CadenceurAppelsMemoire(long delaiMinMs) {
    this.delaiMinMs = delaiMinMs;
  }

  @Override
  public synchronized void attendre() {
    long maintenant = System.currentTimeMillis();
    long prochainAppel = dernierAppelMs.get() + delaiMinMs;
    if (maintenant < prochainAppel) {
      try {
        Thread.sleep(prochainAppel - maintenant);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    dernierAppelMs.set(System.currentTimeMillis());
  }
}
