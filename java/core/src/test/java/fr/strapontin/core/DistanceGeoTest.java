/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link DistanceGeo}.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class DistanceGeoTest {

  @Test
  void metres_memePoint_retourneZero() {
    assertEquals(0.0, DistanceGeo.metres(48.8716, 2.3616, 48.8716, 2.3616), 0.001);
  }

  @Test
  void metres_deuxPointsParisiens_calculeUneDistanceCoherente() {
    // Tour Eiffel (48.8584, 2.2945) / Arc de Triomphe (48.8738, 2.2950) — distance réelle ≈ 1720 m
    final double distance = DistanceGeo.metres(48.8584, 2.2945, 48.8738, 2.2950);
    assertTrue(distance > 1600 && distance < 1800, "distance calculée=" + distance);
  }

  @Test
  void connuesEtProches_distanceSousLeSeuil_retourneTrue() {
    assertTrue(DistanceGeo.connuesEtProches(48.8716, 2.3616, 48.8718, 2.3618, 500));
  }

  // Cas réel #1218 (Alhambra Paris) : ancien théâtre disparu (11e) vs salle de concert active
  // (10e), à plus de 500 m malgré ville/dept identiques.
  @Test
  void connuesEtProches_distanceAuDelaDuSeuil_retourneFalse() {
    assertFalse(DistanceGeo.connuesEtProches(48.8716, 2.3616, 48.867, 2.377, 500));
  }

  @Test
  void connuesEtProches_uneCoordonneeInconnue_retourneFalse() {
    assertFalse(DistanceGeo.connuesEtProches(null, 2.3616, 48.8718, 2.3618, 500));
    assertFalse(DistanceGeo.connuesEtProches(48.8716, null, 48.8718, 2.3618, 500));
    assertFalse(DistanceGeo.connuesEtProches(48.8716, 2.3616, null, 2.3618, 500));
    assertFalse(DistanceGeo.connuesEtProches(48.8716, 2.3616, 48.8718, null, 500));
  }
}
