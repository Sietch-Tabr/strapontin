/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link SiteUsineAChapeaux} sur la page « concerts » réelle du 17/10/2026.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SiteUsineAChapeauxTest {

  private final List<ConcertBrut> concerts =
      SiteUsineAChapeaux.extraire(FixturesSites.lire("usine-concerts.html"));

  @Test
  void extraire_concert_donneArtisteDateEtAdresseSansHeure() {
    final ConcertBrut concert =
        concerts.stream().filter(c -> c.artiste().equals("Abd Al Malik")).findFirst().orElseThrow();

    assertEquals("2026-11-14", concert.date());
    assertNull(concert.heure());
    assertEquals(
        "https://www.usineachapeaux.fr/agenda/abd-al-malik/?occurrence=2026-11-14", concert.url());
  }

  @Test
  void extraire_atelierEtTableRonde_sontEcartes() {
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().startsWith("Booking")));
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().startsWith("Technique")));
  }

  @Test
  void extraire_chaqueDateEstAssocieeAuBonTitre() {
    final ConcertBrut spoink =
        concerts.stream().filter(c -> c.artiste().equals("Spoink")).findFirst().orElseThrow();
    final ConcertBrut cafetera =
        concerts.stream()
            .filter(c -> c.artiste().equals("La Cafetera Roja + Mutinerie"))
            .findFirst()
            .orElseThrow();

    assertEquals("2026-11-13", spoink.date());
    assertEquals("2026-11-28", cafetera.date());
  }

  @Test
  void extraire_chaqueConcert_serattacheALaSalleDuSite() {
    assertTrue(!concerts.isEmpty());
    for (final ConcertBrut concert : concerts) {
      assertEquals("L'Usine à Chapeaux", concert.salle());
      assertEquals("Rambouillet", concert.ville());
      assertEquals("78", concert.dept());
      assertEquals("site_salle:usine", concert.salleSourceId());
    }
  }
}
