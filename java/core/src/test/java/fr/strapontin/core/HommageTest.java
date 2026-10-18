/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link Hommage}.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class HommageTest {

  @Test
  void estHommage_intituleAvecTribute_retourneTrue() {
    assertTrue(Hommage.estHommage("AC/DC Live Tribute by Poun, Black Bomb A, Smack"));
  }

  @Test
  void estHommage_intituleAvecHommageAccentueEnMajuscules_retourneTrue() {
    assertTrue(Hommage.estHommage("HOMMAGE à Johnny"));
  }

  @Test
  void estHommage_nomDArtisteOrdinaire_retourneFalse() {
    assertFalse(Hommage.estHommage("AC/DC"));
  }

  @Test
  void estHommage_motContenantTribute_retourneFalse() {
    assertFalse(Hommage.estHommage("Contributeurs Anonymes"));
  }
}
