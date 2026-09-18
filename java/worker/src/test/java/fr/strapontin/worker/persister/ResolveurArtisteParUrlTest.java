/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link ResolveurArtisteParUrl}.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class ResolveurArtisteParUrlTest {

  private static ValeurConcert concert(
      final String artisteNorm, final String url, final String source) {
    return new ValeurConcert(
        "Artiste",
        artisteNorm,
        LocalDate.of(2027, 3, 12),
        LocalTime.of(20, 0),
        "Salle",
        "salle",
        null,
        null,
        url,
        source,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void resoudreParUrl_aucunConcertEventimAvecUrl_retourneListeInchangeeSansAppelJdbc() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ResolveurArtisteParUrl resolveur = new ResolveurArtisteParUrl(jdbc);
    final List<ValeurConcert> concerts =
        List.of(concert("yael naim", "http://tm/123", "ticketmaster"));

    final List<ValeurConcert> resultat = resolveur.resoudreParUrl(concerts);

    assertSame(concerts, resultat);
    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void resoudreParUrl_aucuneObservationExistante_retourneListeInchangee() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    final ResolveurArtisteParUrl resolveur = new ResolveurArtisteParUrl(jdbc);
    final List<ValeurConcert> concerts =
        List.of(concert("solidays 2026", "http://eventim/festival", "eventim"));

    final List<ValeurConcert> resultat = resolveur.resoudreParUrl(concerts);

    assertSame(concerts, resultat);
  }

  // page festival Eventim : "Solidays 2026" (2ème fetch, region) partage l'URL de "Yael Naim"
  // (1er fetch, batch artiste) déjà en base — l'artiste_norm du 2ème fetch doit s'aligner sur
  // celui déjà persisté pour ne pas créer une seconde ligne.
  @Test
  void resoudreParUrl_urlExistanteArtisteDifferent_uniformiseVersLArtisteExistant() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final UUID artisteIdExistant = UUID.randomUUID();
    final String url = "https://www.eventim.fr/event/solidays-2026/";
    final Map<String, Object> ligneExistante =
        Map.of(
            "artiste_norm",
            "yael naim",
            "artiste_id",
            artisteIdExistant,
            "date",
            Date.valueOf(LocalDate.of(2027, 3, 12)),
            "heure",
            Time.valueOf(LocalTime.of(20, 0)),
            "source",
            "eventim",
            "url",
            url);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneExistante));
    final ResolveurArtisteParUrl resolveur = new ResolveurArtisteParUrl(jdbc);
    final List<ValeurConcert> concerts = List.of(concert("solidays 2026", url, "eventim"));

    final List<ValeurConcert> resultat = resolveur.resoudreParUrl(concerts);

    assertEquals(1, resultat.size());
    assertEquals("yael naim", resultat.get(0).artisteNorm());
    assertEquals(artisteIdExistant, resultat.get(0).artisteId());
  }

  @Test
  void resoudreParUrl_urlExistanteMemeArtiste_neChangeRien() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final String url = "https://www.eventim.fr/event/dej-artiste/";
    final Map<String, Object> ligneExistante =
        Map.of(
            "artiste_norm",
            "dej",
            "date",
            Date.valueOf(LocalDate.of(2027, 3, 12)),
            "heure",
            Time.valueOf(LocalTime.of(20, 0)),
            "source",
            "eventim",
            "url",
            url);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneExistante));
    final ResolveurArtisteParUrl resolveur = new ResolveurArtisteParUrl(jdbc);
    final List<ValeurConcert> concerts = List.of(concert("dej", url, "eventim"));

    final List<ValeurConcert> resultat = resolveur.resoudreParUrl(concerts);

    assertEquals("dej", resultat.get(0).artisteNorm());
  }
}
