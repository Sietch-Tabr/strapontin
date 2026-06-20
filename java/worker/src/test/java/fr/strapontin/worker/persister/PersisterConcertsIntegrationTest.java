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
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM artiste_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
  }

  private static ConcertBrut concertSansUrl(
      String artiste, String date, String salle, String source) {
    return new ConcertBrut(
        artiste, date, "20:30", salle, "Paris", "75", 39.9, 99.9, null, source, "music", "Pop",
        "onsale", null, null, null, null);
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
        "INSERT INTO salles (id, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor"
            + " arena')",
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
  void persisterConcerts_reconciliationSalle_conserveSalleUuid() {
    // ticket #632 — le salleUuid ne doit pas être effacé lors de la réconciliation salle
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm) VALUES ('zenith paris', 'Zénith Paris', 'zenith"
            + " paris')",
        Map.of());
    java.util.UUID salleUuid =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM salles WHERE id = 'zenith paris'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpZenith')",
        Map.of("uuid", salleUuid.toString()));

    // Premier fetch — UUID résolu via salle_sources
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Mika", "2026-10-10", "Zénith Paris", "ticketmaster:KovZpZenith", null)));

    // Deuxième fetch — variante mineure de nom → réconciliation salle déclenchée
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Mika", "2026-10-10", "Zenith de Paris", "ticketmaster:KovZpZenith", null)));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "la réconciliation ne doit pas créer un doublon");

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM concerts WHERE artiste_norm = 'mika'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    assertEquals(
        salleUuid, uuidEnBase, "le salle_uuid ne doit pas être perdu lors de la réconciliation");
  }

  @Test
  void persisterConcerts_salleSourceIdInconnuDeSalleSources_upsertEtAttribueUuid() {
    // ticket #633 — une salle absente de salle_sources doit être upsertée automatiquement
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Stromae", "2026-11-20", "L'Empreinte", "ticketmaster:KovZpEmpreinte", null)));

    Long nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE id = 'l empreinte'", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "la salle doit être créée dans salles");

    Long nbSources =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salle_sources"
                + " WHERE source = 'ticketmaster' AND identifiant_externe = 'KovZpEmpreinte'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbSources, "le mapping source doit être créé dans salle_sources");

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    assertNotNull(uuidEnBase, "concerts.salle_uuid doit être rempli après upsert");
  }

  @Test
  void persisterConcerts_salleAvecIdLegacy_reutiliseSalleExistanteSansDoublon() {
    // Scénario : salle créée avec un id legacy non normalisé (ex. "La Seine Musicale" avec
    // majuscule et espaces). Un fetch TM trouve un venue du même nom → upsertSalleSource
    // doit retrouver la salle par unaccent(lower(nom)) et ne pas créer de doublon.
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm)"
            + " VALUES ('La Seine Musicale', 'La Seine Musicale', 'La Seine Musicale')",
        Map.of());
    java.util.UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM salles WHERE id = 'La Seine Musicale'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Billie Eilish",
                "2026-11-01",
                "LA SEINE MUSICALE",
                "ticketmaster:rZ7SnyZaA4L",
                null)));

    Long nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE unaccent(lower(nom)) = 'la seine musicale'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbSalles, "aucun doublon ne doit être créé");

    java.util.UUID uuidConcert =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM concerts WHERE artiste_norm = 'billie eilish'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    assertEquals(uuidOriginal, uuidConcert, "le concert doit pointer vers la salle originale");

    Long nbSources =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salle_sources WHERE source = 'ticketmaster'"
                + " AND identifiant_externe = 'rZ7SnyZaA4L'"
                + " AND salle_id = CAST(:uuid AS uuid)",
            Map.of("uuid", uuidOriginal.toString()),
            Long.class);
    assertEquals(1L, nbSources, "la salle_source TM doit pointer vers la salle originale");
  }

  @Test
  void persisterConcerts_sallesEnChaineTransitive_retourneUnSeulConcert() {
    // "olympia" ≈ "l olympia" ≈ "l olympia paris" — A≈B et B≈C mais A≢C directement.
    // Sans résolution de chaîne : "olympia" → "l olympia" (1 hop) au lieu de "l olympia paris"
    // → doublon. Avec résolution : toutes convergent vers "l olympia paris" → 1 seul concert.
    // URL null pour que supprimerDoublonsParUrl n'interfère pas dans le comptage.
    List<ConcertBrut> liste =
        List.of(
            concertSansUrl("Mika", "2026-10-01", "Olympia", "ticketmaster"),
            concertSansUrl("Mika", "2026-10-01", "L'Olympia", "ticketmaster"),
            concertSansUrl("Mika", "2026-10-01", "L'Olympia Paris", "ticketmaster"));

    persisterConcerts.persisterConcerts(liste);

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(
        1L, count, "les trois variantes transitives de salle doivent fusionner en un seul concert");
  }

  @Test
  void persisterConcerts_memeVenueIdNomsDifferents_conserveNomAvecDiacritiques() {
    // absorberRenommagesSalles : si plusieurs concerts du même lot ont le même salleSourceId
    // avec des noms différents, le nom avec le meilleur score (diacritiques > tout caps) gagne.
    // Sans le fix, le dernier dans la liste gagnait (ordre non-déterministe).
    jdbc.update(
        "INSERT INTO salles (id, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor"
            + " arena')",
        Map.of());
    java.util.UUID salleUuid =
        jdbc.queryForObject(
            "SELECT salle_uuid FROM salles WHERE id = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_uuid", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpAccor')",
        Map.of("uuid", salleUuid.toString()));

    // Bon nom en premier, mauvais nom en dernier → sans fix, "ACCOR ARENA" aurait gagné.
    List<ConcertBrut> liste =
        List.of(
            concertAvecSourceId(
                "Mika", "2026-10-01", "Accor Aréna", "ticketmaster:KovZpAccor", null),
            concertAvecSourceId(
                "Coldplay", "2026-10-02", "ACCOR ARENA", "ticketmaster:KovZpAccor", null));

    persisterConcerts.persisterConcerts(liste);

    String nomEnBase =
        jdbc.queryForObject(
            "SELECT nom FROM salles WHERE salle_uuid = CAST(:uuid AS uuid)",
            Map.of("uuid", salleUuid.toString()),
            String.class);
    assertEquals("Accor Aréna", nomEnBase, "le nom avec diacritiques doit être préféré");
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
