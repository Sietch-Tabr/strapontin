/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistanceTexteTest {

  @Test
  void similarite_chainesIdentiques_retourne1() {
    assertEquals(1.0, DistanceTexte.similarite("radiohead", "radiohead"));
  }

  @Test
  void similarite_uneChainVide_retourne0() {
    assertEquals(0.0, DistanceTexte.similarite("", "radiohead"));
    assertEquals(0.0, DistanceTexte.similarite("radiohead", ""));
  }

  @Test
  void similarite_variationsProches_superieurOuEgalA085() {
    assertTrue(DistanceTexte.similarite("radiohead", "radioheads") >= 0.85);
    assertTrue(DistanceTexte.similarite("julien clerc", "julien clere") >= 0.85);
  }

  @Test
  void similarite_artistesDistincts_inferieurA085() {
    assertTrue(DistanceTexte.similarite("radiohead", "coldplay") < 0.85);
    assertTrue(DistanceTexte.similarite("beyonce", "adele") < 0.85);
  }

  // --- jaro ---

  @Test
  void jaro_chainesIdentiques_retourne1() {
    assertEquals(1.0, DistanceTexte.jaro("radiohead", "radiohead"));
  }

  @Test
  void jaro_uneChainVide_retourne0() {
    assertEquals(0.0, DistanceTexte.jaro("", "radiohead"));
    assertEquals(0.0, DistanceTexte.jaro("radiohead", ""));
  }

  @Test
  void jaro_krashKarmaVarianteEspaceOmise_superieurOuEgalA095() {
    // "krash karma"/"krashkarma" — une seule différence (espace), doit passer 0.95
    assertTrue(DistanceTexte.jaro("krash karma", "krashkarma") >= 0.95);
  }

  @Test
  void jaro_julienDoreVsJulienMorel_inferieurA095() {
    // Deux artistes distincts avec le même préfixe "julien " — ne doivent pas être fusionnés
    assertTrue(DistanceTexte.jaro("julien dore", "julien morel") < 0.95);
  }

  @Test
  void jaro_artistesDistincts_inferieurA095() {
    assertTrue(DistanceTexte.jaro("radiohead", "coldplay") < 0.95);
    assertTrue(DistanceTexte.jaro("mika", "coldplay") < 0.95);
  }
}
