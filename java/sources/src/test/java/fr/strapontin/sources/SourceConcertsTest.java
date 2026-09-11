/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.core.ConcertBrut;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour les interfaces {@link SourceConcerts} et {@link SourceAvecFetchLot}.
 *
 * Vérifie les comportements par défaut et les implémentations de {@link TicketmasterSource},
 * {@link EventimSource} et {@link DigitickSource}.
 *
 * @since 2.3.1
 * @author Kwaite
 */
class SourceConcertsTest {

  @Test
  void methodesParDefaut_retournentListeVide() {
    SourceConcerts source =
        new SourceConcerts() {
          @Override
          public String nom() {
            return "test";
          }
        };

    assertTrue(source.fetchArtiste("artiste").isEmpty());
    assertTrue(source.fetchArtiste("artiste", "deezer-1", "Q1").isEmpty());
    assertTrue(source.fetchSalle("id").isEmpty());
    assertTrue(source.fetchRegion(List.of("75"), List.of("music")).isEmpty());
    assertFalse(source.accepteIdentifiantSalle("id"));
  }

  // le défaut à 3 paramètres délègue à fetchArtiste(nom) pour toute source qui ne
  // surcharge pas explicitement le repli identité — seule EventimSource en a besoin.
  @Test
  void fetchArtisteAvecIdentite_parDefaut_delegueAFetchArtisteSansIdentite() {
    SourceConcerts source =
        new SourceConcerts() {
          @Override
          public String nom() {
            return "test";
          }

          @Override
          public List<ConcertBrut> fetchArtiste(String nom) {
            return List.of(
                new ConcertBrut(
                    nom,
                    "2026-01-01",
                    null,
                    "salle",
                    null,
                    null,
                    null,
                    "test",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
          }
        };

    List<ConcertBrut> resultat = source.fetchArtiste("artiste", "deezer-1", "Q1");

    assertEquals(1, resultat.size());
    assertEquals("artiste", resultat.get(0).artiste());
  }

  @Test
  void ticketmasterSource_nomEtPrefixe_corrects() {
    TicketmasterSource tm = new TicketmasterSource("fake-key");

    assertEquals("ticketmaster", tm.nom());
    assertTrue(tm.accepteIdentifiantSalle("tm:rZFSnyZdFvA"));
    assertFalse(tm.accepteIdentifiantSalle("digitick:slug"));
    assertFalse(tm.accepteIdentifiantSalle("rZFSnyZdFvA"));
    assertFalse(tm.accepteIdentifiantSalle(null));
  }

  @Test
  void ticketmasterSource_implemente_sourceAvecFetchLot() {
    TicketmasterSource tm = new TicketmasterSource("fake-key");
    assertTrue(tm instanceof SourceAvecFetchLot);
    assertTrue(tm instanceof SourceConcerts);
  }

  @Test
  void eventimSource_nomEtPrefixe_corrects() {
    EventimSource eventim = new EventimSource();

    assertEquals("eventim", eventim.nom());
    assertFalse(eventim.accepteIdentifiantSalle("tm:venue-123"));
    assertFalse(eventim.accepteIdentifiantSalle("eventim:id"));
  }

  @Test
  void eventimSource_implemente_sourceConcerts() {
    EventimSource eventim = new EventimSource();
    assertTrue(eventim instanceof SourceConcerts);
    assertFalse(eventim instanceof SourceAvecFetchLot);
  }

  @Test
  void digitickSource_nomEtPrefixe_corrects() {
    DigitickSource digitick = new DigitickSource();

    assertEquals("digitick", digitick.nom());
    assertTrue(digitick.accepteIdentifiantSalle("digitick:lempreinte"));
    assertFalse(digitick.accepteIdentifiantSalle("tm:rZFSnyZdFvA"));
    assertFalse(digitick.accepteIdentifiantSalle("eventim:123"));
    assertFalse(digitick.accepteIdentifiantSalle(null));
  }

  @Test
  void digitickSource_implemente_sourceConcerts() {
    DigitickSource digitick = new DigitickSource();
    assertTrue(digitick instanceof SourceConcerts);
    assertFalse(digitick instanceof SourceAvecFetchLot);
  }

  @Test
  void sourceAvecFetchLot_fetchArtistesLot_vide_retourneListeVide() {
    TicketmasterSource tm = new TicketmasterSource("fake-key");
    List<ConcertBrut> resultat = tm.fetchArtistesLot(List.of());
    assertTrue(resultat.isEmpty());
  }
}
