/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources.sites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.CorrespondanceSalle;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link SiteEmbSannois} sur la page agenda réelle du 17/10/2026.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SiteEmbSannoisTest {

  private final List<ConcertBrut> concerts =
      SiteEmbSannois.extraire(FixturesSites.lire("emb-agenda.html"));

  @Test
  void extraire_concertAvecPremierePartie_garderLaTeteDAffiche() {
    final ConcertBrut concert =
        concerts.stream()
            .filter(c -> c.artiste().equals("COLOURS IN THE STREET"))
            .findFirst()
            .orElseThrow();

    assertEquals("2026-11-06", concert.date());
    assertEquals("20:30", concert.heure());
    assertEquals(
        "https://embsannois.fr/evenement/colours-in-the-street-emb-sannois/", concert.url());
  }

  @Test
  void extraire_atelierSurPlusieursJours_estEcarte() {
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().startsWith("LABOMELO")));
  }

  @Test
  void extraire_spectacleJeunePublic_estEcarte() {
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().startsWith("Jeune public")));
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().startsWith("MEET UP")));
  }

  @Test
  void extraire_afterwork_perdSonPrefixeDeSoiree() {
    assertTrue(concerts.stream().anyMatch(c -> c.artiste().equals("ZAOUI")));
    assertTrue(concerts.stream().noneMatch(c -> c.artiste().toLowerCase().startsWith("afterwork")));
  }

  @Test
  void extraire_chaqueConcert_serattacheALaSalleDuSite() {
    assertTrue(!concerts.isEmpty());
    for (final ConcertBrut concert : concerts) {
      assertEquals("Espace Michel Berger", concert.salle());
      assertEquals("95", concert.dept());
      assertEquals("site_salle:emb", concert.salleSourceId());
      assertEquals("95110", concert.position().codePostal());
    }
  }

  @Test
  void lieu_compareAuNomPublieParEventim_estLaMemeSalle() {
    assertTrue(
        CorrespondanceSalle.estDoublonSalle(
            "espace michel berger", "emb espace michel berger sannois"));
  }
}
