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
 * Tests unitaires de {@link PositionLieu} et {@link IdentifiantLieuEventim}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class PositionLieuTest {

  @Test
  void depuis_coordonneesTexteTicketmaster_lesConvertit() {
    final PositionLieu position = PositionLieu.depuis("48.8380", "2.3785", "75012");

    assertTrue(position.estGeolocalisee());
    assertEquals(48.838, position.latitude(), 1e-9);
    assertEquals("75012", position.codePostal());
  }

  @Test
  void constructeur_coordonneesNullesOuInvalides_lesEcarte() {
    assertFalse(PositionLieu.depuis(0.0, 0.0, null).estGeolocalisee());
    assertFalse(PositionLieu.depuis("abc", "2.3", null).estGeolocalisee());
    assertFalse(new PositionLieu(91.0, 2.0, null).estGeolocalisee());
  }

  @Test
  void constructeur_codePostalMalForme_lEcarte() {
    assertNull(new PositionLieu(null, null, "L-1234").codePostal());
    assertEquals("13001", new PositionLieu(null, null, " 13001 ").codePostal());
  }

  @Test
  void identifiantLieuEventim_codePostalConnu_qualifieEtResteRelisible() {
    final String identifiant = IdentifiantLieuEventim.calculer("palais des congres", "66000");

    assertEquals("internal:palais des congres@66000", identifiant);
    assertEquals("internal:palais des congres", IdentifiantLieuEventim.historique(identifiant));
    assertEquals("palais des congres", IdentifiantLieuEventim.nom(identifiant));
  }

  @Test
  void identifiantLieuEventim_codePostalInconnu_formeHistorique() {
    assertEquals(
        "internal:palais des congres", IdentifiantLieuEventim.calculer("palais des congres", null));
    assertEquals(
        "internal:palais des congres",
        IdentifiantLieuEventim.historique("internal:palais des congres"));
  }
}
