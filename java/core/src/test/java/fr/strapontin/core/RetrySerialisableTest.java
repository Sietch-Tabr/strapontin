/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Tests unitaires de {@link RetrySerialisable}.
 */
class RetrySerialisableTest {

  private static final Logger log = LoggerFactory.getLogger(RetrySerialisableTest.class);

  @Test
  void executer_succesDuPremierCoup_retourneLeResultatSansRetry() {
    AtomicInteger appels = new AtomicInteger();
    int resultat =
        RetrySerialisable.executer(
            3,
            log,
            "test",
            () -> {
              appels.incrementAndGet();
              return 42;
            });
    assertEquals(42, resultat);
    assertEquals(1, appels.get());
  }

  @Test
  void executer_conflitPuisSucces_retenteEtRetourneLeResultat() {
    AtomicInteger appels = new AtomicInteger();
    int resultat =
        RetrySerialisable.executer(
            3,
            log,
            "test",
            () -> {
              int n = appels.incrementAndGet();
              if (n < 2) throw new PessimisticLockingFailureException("conflit");
              return 42;
            });
    assertEquals(42, resultat);
    assertEquals(2, appels.get());
  }

  @Test
  void executer_conflitPersistant_relanceApresMaxTentatives() {
    AtomicInteger appels = new AtomicInteger();
    assertThrows(
        PessimisticLockingFailureException.class,
        () ->
            RetrySerialisable.executer(
                3,
                log,
                "test",
                () -> {
                  appels.incrementAndGet();
                  throw new PessimisticLockingFailureException("conflit");
                }));
    assertEquals(3, appels.get());
  }

  @Test
  void executer_variantesRunnable_executeLOperation() {
    AtomicInteger appels = new AtomicInteger();
    RetrySerialisable.executer(3, log, "test", appels::incrementAndGet);
    assertTrue(appels.get() >= 1);
  }
}
