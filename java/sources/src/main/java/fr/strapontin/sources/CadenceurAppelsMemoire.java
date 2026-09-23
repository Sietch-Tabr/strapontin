/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implémentation en mémoire de {@link CadenceurAppels} : verrou sur
 * l'instance, coordonne les appels d'un seul processus quel que soit le
 * nombre de jobs Quartz en parallèle. Une instance par source.
 *
 * @since 2.7.0
 * @author Kwaite
 */
final class CadenceurAppelsMemoire implements CadenceurAppels {

  private final long delaiMinMs;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);
  private final ReentrantLock verrou = new ReentrantLock();

  /**
   * @param delaiMinMs écart minimal en millisecondes entre deux appels
   *     successifs
   */
  /* package-private : instancié uniquement au sein du package sources. */
  CadenceurAppelsMemoire(final long delaiMinMs) {
    this.delaiMinMs = delaiMinMs;
  }

  @Override
  public void attendre() {
    verrou.lock();
    try {
      final long maintenant = System.currentTimeMillis();
      final long prochainAppel = dernierAppelMs.get() + delaiMinMs;
      if (maintenant < prochainAppel) {
        try {
          Thread.sleep(prochainAppel - maintenant);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      dernierAppelMs.set(System.currentTimeMillis());
    } finally {
      verrou.unlock();
    }
  }
}
