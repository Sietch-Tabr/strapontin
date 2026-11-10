/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TitresDeSpectacleTest {

  @Test
  void sontVariantes_memeProgrammeSousDeuxIntitules_retourneVrai() {
    assertTrue(
        TitresDeSpectacle.sontVariantes(
            "Les 4 Saisons de Vivaldi, Ave Maria et Célèbres Adagios",
            "Extraits, Ave Maria - Les 4 Saisons de Vivaldi"));
  }

  @Test
  void sontVariantes_programmesSansRapport_retourneFaux() {
    assertFalse(
        TitresDeSpectacle.sontVariantes(
            "Le Messie de Haendel en concert", "Les Quatre Saisons de Vivaldi en concert"));
  }

  @Test
  void sontVariantes_unSeulMotCommun_retourneFaux() {
    assertFalse(TitresDeSpectacle.sontVariantes("Requiem de Mozart", "Mozart symphonies"));
  }

  @Test
  void sontVariantes_titreNulOuVide_retourneFaux() {
    assertFalse(TitresDeSpectacle.sontVariantes(null, "Vivaldi Saisons"));
    assertFalse(TitresDeSpectacle.sontVariantes("", "Vivaldi Saisons"));
  }
}
