/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JetonInterneTest {

  @Test
  void estValide_secretEtEnTeteIdentiques_retourneVrai() {
    assertTrue(JetonInterne.estValide("s3cret", "Bearer s3cret"));
  }

  @Test
  void estValide_secretDifferentOuAbsentDeLEnTete_retourneFaux() {
    assertFalse(JetonInterne.estValide("s3cret", "Bearer autre"));
    assertFalse(JetonInterne.estValide("s3cret", ""));
    assertFalse(JetonInterne.estValide("s3cret", "s3cret"));
  }

  @Test
  void estValide_secretNonConfigure_refuseMemeUnEnTeteVide() {
    assertFalse(JetonInterne.estValide("", "Bearer "));
    assertFalse(JetonInterne.estValide("  ", "Bearer   "));
  }

  @Test
  void refus_retourneUne403() {
    assertEquals(403, JetonInterne.refus().getStatusCode().value());
  }
}
