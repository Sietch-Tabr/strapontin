/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.TicketmasterSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/// Tests d'intégration de [PersisterConcerts] avec PostgreSQL réel (Testcontainers).
///
/// @since 2.0.0
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class PersisterConcertsIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean @SuppressWarnings("unused")
  TicketmasterSource ticketmasterSource;

  @Autowired private PersisterConcerts persisterConcerts;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM concerts", Map.of());
  }

  private static ConcertBrut concert(String artiste, String date, String salle, String source) {
    return new ConcertBrut(
        artiste,
        date,
        "20:30",
        salle,
        "Paris",
        "75",
        39.9,
        99.9,
        "https://example.com/" + artiste.toLowerCase().replace(" ", "-"),
        source,
        "music",
        "Pop",
        "onsale");
  }

  @Test
  void persisterConcerts_concertValide_insereEnBase() {
    List<ConcertBrut> liste =
        List.of(concert("Julien Clerc", "2026-09-15", "Accor Arena", "ticketmaster"));

    PersisterConcerts.ResultatPersistance res = persisterConcerts.persisterConcerts(liste);

    assertEquals(1, res.nbInseres());
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_upsertConflitMemeArtisteDateSalle_metsAJourPasInsertion() {
    ConcertBrut v1 = concert("Mika", "2026-07-10", "Olympia", "ticketmaster");
    ConcertBrut v2 =
        new ConcertBrut(
            "Mika",
            "2026-07-10",
            "21:00",
            "Olympia",
            "Paris",
            "75",
            45.0,
            110.0,
            "https://example.com/mika-v2",
            "eventim",
            "music",
            "Pop",
            null);

    persisterConcerts.persisterConcerts(List.of(v1));
    persisterConcerts.persisterConcerts(List.of(v2));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_preferenceArtisteDiacritiques_conserveNomAvecAccents() {
    List<ConcertBrut> liste =
        List.of(
            new ConcertBrut(
                "Julien Dore",
                "2026-08-20",
                "20:00",
                "Zenith Paris",
                "Paris",
                "75",
                null,
                null,
                null,
                "ticketmaster",
                "music",
                "Pop",
                null),
            new ConcertBrut(
                "Julien Doré",
                "2026-08-20",
                "20:00",
                "Zénith Paris",
                "Paris",
                "75",
                null,
                null,
                null,
                "eventim",
                "music",
                "Pop",
                null));

    persisterConcerts.persisterConcerts(liste);

    String artiste =
        jdbc.queryForObject(
            "SELECT artiste FROM concerts WHERE artiste_norm = 'julien dore'",
            Map.of(),
            String.class);
    assertEquals("Julien Doré", artiste);
  }

  @Test
  void persisterConcerts_listeVide_retourneIdsVides() {
    PersisterConcerts.ResultatPersistance res = persisterConcerts.persisterConcerts(List.of());
    assertEquals(0, res.nbInseres() + res.nbMisAJour());
  }

  @Test
  void persisterConcerts_artisteNormVide_rejetteLeConcert() {
    ConcertBrut concert =
        new ConcertBrut(
            "???",
            "2026-09-01",
            null,
            "Salle",
            null,
            null,
            null,
            null,
            null,
            "ticketmaster",
            null,
            null,
            null);

    PersisterConcerts.ResultatPersistance res =
        persisterConcerts.persisterConcerts(List.of(concert));

    assertEquals(0, res.nbInseres() + res.nbMisAJour());
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(0L, count);
  }
}
