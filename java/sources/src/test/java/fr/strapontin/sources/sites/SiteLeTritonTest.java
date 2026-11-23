/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link SiteLeTriton} sur la page agenda réelle du 17/10/2026.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SiteLeTritonTest {

  private final List<ConcertBrut> concerts =
      SiteLeTriton.extraire(FixturesSites.lire("triton-agenda.html"));

  @Test
  void extraire_pageAgenda_retourneUnConcertParFiche() {
    assertEquals(6, concerts.size());
  }

  @Test
  void extraire_premiereFiche_donneArtisteDateHeureEtAdresse() {
    final ConcertBrut concert = concerts.getFirst();

    assertEquals("Martial Bort", concert.artiste());
    assertEquals("2026-10-17", concert.date());
    assertEquals("20:30", concert.heure());
    assertEquals("https://letriton.com/concert/martial-bort-2026-10-17", concert.url());
  }

  @Test
  void extraire_sousTitreDeTournee_neFaitPasPartieDeLArtiste() {
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().contains("—")));
    assertTrue(
        concerts.stream()
            .anyMatch(
                c -> c.artiste().equals("Antonio Barcelona") && c.date().equals("2026-11-05")));
  }

  private static String fiche(final String adresse) {
    return "<article class=\"crow\"><a class=\"crow__main\" href=\""
        + adresse
        + "\"></a><h3 class=\"crow__title\">Antonio Barcelona</h3>"
        + "<div class=\"crow__when\">jeudi 21 janvier 20h30</div></article>";
  }

  @Test
  void extraire_adresseAvecSuffixeDeDoublon_litLaDateDeLaFiche() {
    final List<ConcertBrut> lus =
        SiteLeTriton.extraire(fiche("https://letriton.com/concert/antonio-barcelona-2027-01-21-2"));

    assertEquals(1, lus.size());
    assertEquals("2027-01-21", lus.getFirst().date());
    assertEquals("20:30", lus.getFirst().heure());
    assertEquals(
        "https://letriton.com/concert/antonio-barcelona-2027-01-21-2", lus.getFirst().url());
  }

  @Test
  void extraire_adresseSansDate_ignoreLaFiche() {
    assertTrue(
        SiteLeTriton.extraire(fiche("https://letriton.com/concert/antonio-barcelona")).isEmpty());
  }

  @Test
  void extraire_chaqueConcert_serattacheALaSalleDuSite() {
    for (final ConcertBrut concert : concerts) {
      assertEquals("Le Triton", concert.salle());
      assertEquals("Les Lilas", concert.ville());
      assertEquals("93", concert.dept());
      assertEquals("site_salle", concert.source());
      assertEquals("site_salle:triton", concert.salleSourceId());
    }
  }

  @Test
  void extraire_pageSansFiche_retourneUneListeVide() {
    assertTrue(SiteLeTriton.extraire("<html><body><p>Maintenance</p></body></html>").isEmpty());
  }
}
