/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Boucle de retry sur conflit de sérialisation, partagée par
 * les services isolant une transaction {@code SERIALIZABLE}
 * via auto-injection par setter (détail : CLAUDE.md, Sécurité
 * admin/sessions).
 */
public final class RetrySerialisable {

  private RetrySerialisable() {}

  /**
   * Exécute {@code operation} en retentant sur conflit de
   * sérialisation, jusqu'à {@code maxTentatives} fois.
   * @param maxTentatives nombre max. de tentatives avant relance
   * @param log logger de l'appelant, pour tracer les tentatives
   * @param contexte description de l'opération (message de log)
   * @param operation opération transactionnelle à exécuter
   */
  public static <T> T executer(
      final int maxTentatives,
      final Logger log,
      final String contexte,
      final Supplier<T> operation) {
    for (int tentative = 1; ; tentative++) {
      try {
        return operation.get();
      } catch (PessimisticLockingFailureException e) {
        if (tentative >= maxTentatives) {
          throw e;
        }
        log.warn(
            "Conflit de sérialisation lors de {} — nouvelle tentative ({}/{})",
            contexte,
            tentative,
            maxTentatives);
      }
    }
  }

  /**
   * Variante sans valeur de retour.
   * @param maxTentatives nombre max. de tentatives avant relance
   * @param log logger de l'appelant, pour tracer les tentatives
   * @param contexte description de l'opération (message de log)
   * @param operation opération transactionnelle à exécuter
   */
  public static void executer(
      final int maxTentatives, final Logger log, final String contexte, final Runnable operation) {
    executer(
        maxTentatives,
        log,
        contexte,
        () -> {
          operation.run();
          return null;
        });
  }
}
