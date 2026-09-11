/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Boucle de retry sur conflit de sérialisation, partagée par les services qui isolent un
 * COUNT/lecture-puis-écriture dans une transaction {@code SERIALIZABLE} via le pattern
 * d'auto-injection par setter (bean proxifié appelé via {@code self}, jamais directement — sans
 * quoi l'appel contournerait le proxy AOP Spring et l'isolation ne s'appliquerait pas).
 *
 * L'appelant reste responsable de la méthode transactionnelle elle-même ({@code @Transactional(
 * isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)}) — ce contrat ne
 * peut pas être factorisé ici, l'annotation devant porter sur une méthode de l'appelant pour que
 * le proxy AOP Spring l'intercepte.
 */
public final class RetrySerialisable {

  private RetrySerialisable() {}

  /**
   * Exécute {@code operation} en retentant jusqu'à {@code maxTentatives} fois sur conflit de
   * sérialisation.
   *
   * @param maxTentatives nombre maximal de tentatives avant de relancer l'exception
   * @param log logger de l'appelant, pour tracer les tentatives de retry
   * @param contexte description de l'opération, utilisée dans le message de log de retry
   * @param operation opération transactionnelle à exécuter (typiquement un appel à {@code
   *   self.xxxTransactionnel(...)})
   * @return le résultat de {@code operation}
   * @throws PessimisticLockingFailureException si le conflit persiste après {@code maxTentatives}
   *   tentatives
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
   *
   * @param maxTentatives nombre maximal de tentatives avant de relancer l'exception
   * @param log logger de l'appelant, pour tracer les tentatives de retry
   * @param contexte description de l'opération, utilisée dans le message de log de retry
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
