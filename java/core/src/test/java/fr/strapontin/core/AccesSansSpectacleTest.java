/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccesSansSpectacleTest {

  @Test
  void estAccesSansSpectacle_accesPrioritaire_retourneVrai() {
    assertTrue(AccesSansSpectacle.estAccesSansSpectacle("M E N Arena Priority Pass"));
    assertTrue(AccesSansSpectacle.estAccesSansSpectacle("XTB Priority Access 2027"));
    assertTrue(AccesSansSpectacle.estAccesSansSpectacle("Fast Track Zénith"));
  }

  @Test
  void estAccesSansSpectacle_visiteSeule_retourneVrai() {
    assertTrue(AccesSansSpectacle.estAccesSansSpectacle("Visite des Rex Studios"));
    assertTrue(AccesSansSpectacle.estAccesSansSpectacle("  VISITES guidées du Grand Rex"));
  }

  @Test
  void estAccesSansSpectacle_billetCombineAvecSpectacle_retourneFaux() {
    assertFalse(
        AccesSansSpectacle.estAccesSansSpectacle(
            "La Féerie des Eaux + Billie, à la croisée des mondes + Visite des Rex Studios"));
    assertFalse(AccesSansSpectacle.estAccesSansSpectacle("Concert + Priority Pass"));
  }

  @Test
  void estAccesSansSpectacle_titreDeSpectacle_retourneFaux() {
    assertFalse(AccesSansSpectacle.estAccesSansSpectacle("La Visite de la vieille dame"));
    assertFalse(AccesSansSpectacle.estAccesSansSpectacle("Sting"));
    assertFalse(AccesSansSpectacle.estAccesSansSpectacle(null));
  }

  @Test
  void estAccesSansSpectacle_adresseDePrestation_retourneVrai() {
    assertTrue(
        AccesSansSpectacle.estAccesSansSpectacle(
            "Celine Dion", "https://x.fr/prestation-hospitalite-celine-dion-billet/idmanif/1"));
    assertTrue(
        AccesSansSpectacle.estAccesSansSpectacle("Rush", "https://x.fr/prestation-rush-billet/1"));
    assertTrue(
        AccesSansSpectacle.estAccesSansSpectacle(
            "XTB", "https://x.fr/xtb-priority-access-2027-billet/1"));
  }

  @Test
  void estAccesSansSpectacle_adresseOrdinaire_retourneFaux() {
    assertFalse(
        AccesSansSpectacle.estAccesSansSpectacle(
            "Celine Dion", "https://x.fr/celine-dion-billet/idmanif/1"));
    assertFalse(AccesSansSpectacle.estAccesSansSpectacle("Celine Dion", null));
  }
}
