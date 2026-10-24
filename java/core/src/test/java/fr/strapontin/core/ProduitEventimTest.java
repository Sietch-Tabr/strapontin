/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProduitEventimTest {

  @Test
  void identifiant_adresseAvecTitre_retourneLIdentifiantFinal() {
    assertEquals(
        Optional.of("22026717"),
        ProduitEventim.identifiant(
            "https://www.eventim.fr/event/system-of-a-down-live-tribute-le-balajo-22026717"));
  }

  @Test
  void identifiant_titreModifie_retourneLeMemeIdentifiant() {
    assertEquals(
        ProduitEventim.identifiant("https://www.eventim.fr/event/bejart-ballet-lausanne-22173044"),
        ProduitEventim.identifiant("https://www.eventim.fr/event/bolero-22173044/"));
  }

  @Test
  void identifiant_adresseSansIdentifiant_retourneVide() {
    assertTrue(
        ProduitEventim.identifiant("https://www.eventim.fr/earl-sweatshirt-mike-billet").isEmpty());
    assertTrue(ProduitEventim.identifiant(null).isEmpty());
  }

  @Test
  void identifiant_chiffresDansLeTitre_neSontPasRetenus() {
    assertTrue(ProduitEventim.identifiant("https://www.eventim.fr/event/le-102-billet").isEmpty());
  }
}
