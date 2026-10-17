/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LimiteurDebitTest {

  private static LimiteurDebit limiteur(final int capacite) {
    return new LimiteurDebit(capacite, Duration.ofHours(1), Duration.ofHours(2), 100);
  }

  @Test
  void autoriser_quotaEpuise_refuse() {
    final LimiteurDebit limiteur = limiteur(2);
    assertTrue(limiteur.autoriser("a"));
    assertTrue(limiteur.autoriser("a"));
    assertFalse(limiteur.autoriser("a"));
  }

  @Test
  void autoriser_clesDifferentes_quotasIndependants() {
    final LimiteurDebit limiteur = limiteur(1);
    assertTrue(limiteur.autoriser("a"));
    assertTrue(limiteur.autoriser("b"));
    assertFalse(limiteur.autoriser("a"));
  }

  @Test
  void reinitialiser_apresEpuisement_reautorise() {
    final LimiteurDebit limiteur = limiteur(1);
    limiteur.autoriser("a");
    assertFalse(limiteur.autoriser("a"));
    limiteur.reinitialiser();
    assertTrue(limiteur.autoriser("a"));
  }
}
