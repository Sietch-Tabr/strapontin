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

  // Plafond de l'écart après des refus répétés, en multiple de l'écart de base.
  private static final int FACTEUR_MAX = 8;
  // Appels acceptés d'affilée avant de raccourcir l'écart d'un cran.
  private static final int SUCCES_POUR_APAISER = 20;

  private final long delaiMinMs;
  private final AtomicLong ecartCourantMs;
  private final AtomicLong succesConsecutifs = new AtomicLong(0);
  private final AtomicLong dernierAppelMs = new AtomicLong(0);
  private final ReentrantLock verrou = new ReentrantLock();

  /**
   * @param delaiMinMs écart minimal en millisecondes entre deux appels
   *     successifs
   */
  /* package-private : instancié uniquement au sein du package sources. */
  CadenceurAppelsMemoire(final long delaiMinMs) {
    this.delaiMinMs = delaiMinMs;
    this.ecartCourantMs = new AtomicLong(delaiMinMs);
  }

  @Override
  public void attendre() {
    verrou.lock();
    try {
      final long maintenant = System.currentTimeMillis();
      final long prochainAppel = dernierAppelMs.get() + ecartCourantMs.get();
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

  @Override
  public void ralentir() {
    succesConsecutifs.set(0);
    ecartCourantMs.updateAndGet(courant -> Math.min(courant * 2, delaiMinMs * FACTEUR_MAX));
  }

  @Override
  public void apaiser() {
    if (succesConsecutifs.incrementAndGet() >= SUCCES_POUR_APAISER) {
      succesConsecutifs.set(0);
      ecartCourantMs.updateAndGet(courant -> Math.max(courant / 2, delaiMinMs));
    }
  }

  /**
   * @return écart minimal actuellement appliqué entre deux appels
   */
  /* package-private : lu par les tests du package. */
  long delaiCourantMs() {
    return ecartCourantMs.get();
  }
}
