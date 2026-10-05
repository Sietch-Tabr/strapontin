/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link JetonInterneWorker}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class JetonInterneWorkerTest {

  @Test
  void calculer_memeSecret_memeJetonDansLesDeuxProcessus() {
    assertEquals(JetonInterneWorker.calculer("secret"), JetonInterneWorker.calculer("secret"));
    assertNotEquals(JetonInterneWorker.calculer("secret"), JetonInterneWorker.calculer("autre"));
  }

  @Test
  void estValide_jetonCalculeAvecLeMemeSecret_accepte() {
    assertTrue(JetonInterneWorker.estValide("secret", JetonInterneWorker.calculer("secret")));
  }

  @Test
  void estValide_jetonAbsentFauxOuSecretVide_refuse() {
    assertFalse(JetonInterneWorker.estValide("secret", null));
    assertFalse(JetonInterneWorker.estValide("secret", "faux"));
    assertFalse(JetonInterneWorker.estValide("secret", JetonInterneWorker.calculer("autre")));
    assertFalse(JetonInterneWorker.estValide("", "quelconque"));
    assertFalse(JetonInterneWorker.estValide(null, "quelconque"));
  }

  @Test
  void calculer_secretVide_leveUneErreurExplicite() {
    assertThrows(IllegalArgumentException.class, () -> JetonInterneWorker.calculer(" "));
  }
}
