/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import java.util.concurrent.atomic.AtomicLong;

/// Garantit un écart minimal entre deux appels HTTP vers un même service externe, quel que soit le
/// nombre de jobs Quartz en parallèle. Le verrou est sur l'instance — une instance par source ayant
/// son propre rythme de cadence.
final class CadenceurAppels {

  private final long delaiMinMs;
  private final AtomicLong dernierAppelMs = new AtomicLong(0);

  /// @param delaiMinMs écart minimal en millisecondes entre deux appels successifs
  CadenceurAppels(long delaiMinMs) {
    this.delaiMinMs = delaiMinMs;
  }

  /// Bloque l'appelant si nécessaire pour respecter l'écart minimal depuis le dernier appel.
  synchronized void attendre() {
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
