/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link VapidKeyGenerator}.
 *
 * @since 2.8.7
 * @author Kwaite
 */
class VapidKeyGeneratorTest {

  @Test
  void genererPaire_nominal_produitUnPointNonCompresseDe65Octets() throws Exception {
    VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();

    assertNotNull(paire.clePublique());
    assertNotNull(paire.clePrivee());
    byte[] pub = Base64.getUrlDecoder().decode(paire.clePublique());
    assertEquals(65, pub.length);
    assertEquals(0x04, pub[0], "point non compressé attendu (préfixe 0x04)");
  }

  @Test
  void genererPaire_clePriveeDecodable_produit32Octets() throws Exception {
    VapidKeyGenerator.PaireVapid paire = VapidKeyGenerator.genererPaire();

    byte[] prive = Base64.getUrlDecoder().decode(paire.clePrivee());
    assertEquals(32, prive.length);
  }

  @Test
  void genererPaire_deuxAppels_produitDesClesDistinctes() throws Exception {
    VapidKeyGenerator.PaireVapid paire1 = VapidKeyGenerator.genererPaire();
    VapidKeyGenerator.PaireVapid paire2 = VapidKeyGenerator.genererPaire();

    assertNotEquals(paire1.clePublique(), paire2.clePublique());
    assertNotEquals(paire1.clePrivee(), paire2.clePrivee());
  }
}
