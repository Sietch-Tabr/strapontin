/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de {@link QualificatifSalle}.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class QualificatifSalleTest {

  @Test
  void extraireBase_nomQualifieDUneSalleInterne_retourneLeComplexe() {
    assertEquals(
        "La Seine Musicale", QualificatifSalle.extraireBase("La Seine Musicale - Grande Seine"));
  }

  @Test
  void extraireBase_pseudoLieuDiversLieux_retourneNull() {
    assertNull(QualificatifSalle.extraireBase("Pigalle / Montmartre - Divers lieux"));
    assertNull(QualificatifSalle.extraireBase("Quartier Latin - Plusieurs lieux"));
  }

  @Test
  void qualificatifAbsentDe_pseudoLieu_neDesignePasUneSousSalle() {
    assertFalse(
        QualificatifSalle.qualificatifAbsentDe(
            "Pigalle / Montmartre - Divers lieux", "theatre montmartre galabru"));
  }

  @Test
  void qualificatifAbsentDe_vraiQualificatifAbsent_retourneTrue() {
    assertTrue(
        QualificatifSalle.qualificatifAbsentDe(
            "La Seine Musicale - Grande Seine", "la seine musicale"));
  }
}
