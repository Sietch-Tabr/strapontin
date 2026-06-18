/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Tests d'intégration de [PersisterConcerts] avec PostgreSQL réel (Testcontainers).
///
/// @since 2.0.0
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class PersisterConcertsIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean
  @SuppressWarnings("unused")
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
        "onsale",
        null,
        null,
        null,
        null);
  }

  private static ConcertBrut concertAvecSourceId(
      String artiste, String date, String salle, String salleSourceId, String artisteSourceId) {
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
        "ticketmaster",
        "music",
        "Pop",
        "onsale",
        null,
        null,
        salleSourceId,
        artisteSourceId);
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
            null,
            null,
            null,
            null,
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
                null,
                null,
                null,
                null,
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
                null,
                null,
                null,
                null,
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
  void persisterConcerts_artisteRenommeAvecSuffixeNumerique_metsAJourPasInsertion() {
    // ticket #619 — "Star Academy" -> "Star Academy 1" entre deux fetches TM, même salle/date
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy", "2026-06-28", "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy 1", "2026-06-28", "Accor Arena", "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artisteRenommeAvecArticleAjoute_metsAJourPasInsertion() {
    // ticket #619 — "Comédies Musicales" -> "Les Comédies Musicales" entre deux fetches TM
    persisterConcerts.persisterConcerts(
        List.of(
            concert(
                "Comédies Musicales", "2026-06-19", "Cirque D'Hiver Bouglione", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(
            concert(
                "Les Comédies Musicales",
                "2026-06-19",
                "Cirque D'Hiver Bouglione",
                "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artistesDistinctsMemeSalleEtDate_conserveLesDeuxConcerts() {
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink", "2026-06-28", "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink Floyd", "2026-06-28", "Accor Arena", "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(2L, count);
  }

  @Test
  void persisterConcerts_salleSourceIdConnu_reutiliseSalleUuid() {
    // Pré-condition : salle déjà en base avec son UUID source TM
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor arena')",
        Map.of());
    java.util.UUID salleUuid =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM salles WHERE id = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpaZGFe')",
        Map.of("uuid", salleUuid.toString()));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Stromae", "2026-10-01", "Accor Arena", "ticketmaster:KovZpaZGFe", null)));

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    assertEquals(salleUuid, uuidEnBase);
  }

  @Test
  void persisterConcerts_salleRenommee_metAJourNomConserveSalleUuid() {
    // Scénario Plenitude Arena : même venueId TM, nom différent entre deux fetches
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm)"
            + " VALUES ('paris la defense arena', 'Paris La Défense Arena',"
            + " 'paris la defense arena')",
        Map.of());
    java.util.UUID salleUuid =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM salles WHERE id = 'paris la defense arena'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpbFGFe')",
        Map.of("uuid", salleUuid.toString()));

    // Fetch avec le nouveau nom
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Dua Lipa", "2026-11-15", "Plenitude Arena", "ticketmaster:KovZpbFGFe", null)));

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM concerts WHERE artiste_norm = 'dua lipa'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    assertEquals(salleUuid, uuidEnBase, "le salle_uuid doit être stable après renommage");

    String nomMisAJour =
        jdbc.queryForObject(
            "SELECT nom FROM salles WHERE salle_uuid = CAST(:uuid AS uuid)",
            Map.of("uuid", salleUuid.toString()),
            String.class);
    assertEquals("Plenitude Arena", nomMisAJour, "le nom de la salle doit être mis à jour");
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
            null,
            null,
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
