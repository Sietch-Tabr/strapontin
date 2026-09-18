/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FiltreArtisteTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Tribute to Nina Simone",
        "A Tribute to Queen",
        "tribute to pink floyd",
        "Hommage à Piaf",
        "Hommage à Johnny Hallyday",
        "Pink Floyd Cover of The Wall",
      })
  void estTribute_nomTributeOuHommage_retourneTrue(final String nom) {
    assertTrue(FiltreArtiste.estTribute(nom));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Nina Simone", "Queen", "Pink Floyd", "Piaf", "Coverdale"})
  void estTribute_nomArtisteOriginal_retourneFalse(final String nom) {
    assertFalse(FiltreArtiste.estTribute(nom));
  }

  @Test
  void estTribute_nomNull_retourneFalse() {
    assertFalse(FiltreArtiste.estTribute(null));
  }
}
