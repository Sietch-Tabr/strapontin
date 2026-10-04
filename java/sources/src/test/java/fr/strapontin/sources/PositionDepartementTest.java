/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plausibilité d'une position pour un département ({@link PositionDepartement}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class PositionDepartementTest {

  @Test
  void plausible_balajoDansLe75_vrai() {
    assertTrue(PositionDepartement.plausible("75", 48.8540169, 2.3721601));
  }

  // « Grand Palais », sans ville : Nominatim renvoyait un lieu-dit de Charente.
  @Test
  void plausible_grandPalaisGeocodeEnCharente_faux() {
    assertFalse(PositionDepartement.plausible("75", 45.1388794, -0.155064));
  }

  @Test
  void plausible_salleEnLimiteDeDepartement_vrai() {
    // Carré des Docks, Le Havre : à 0,8 rayon du centre de la Seine-Maritime.
    assertTrue(PositionDepartement.plausible("76", 49.48827, 0.12946));
  }

  @Test
  void plausible_departementInconnu_vrai() {
    assertTrue(PositionDepartement.plausible(null, 45.0, 0.0));
    assertTrue(PositionDepartement.plausible("L5", 45.0, 0.0));
  }
}
