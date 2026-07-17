/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.TicketmasterSource;
import java.time.LocalDate;
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

  // Date relative à "aujourd'hui" (comme RecalculerMatchesServiceIntegrationTest#DATE_FUTURE) —
  // ReconciliateurArtistes.reconcilierArtistes ne charge que les concerts des 7 derniers jours
  // pour la réconciliation inter-jobs ; une date figée finit toujours par sortir de cette
  // fenêtre au fil du temps (constaté sur "2026-06-28", devenu invalide après le 5 juillet).
  private static final String DATE_RECENTE = LocalDate.now().toString();

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
        artiste, date, "20:30", salle, "Paris", "75", null, source, "music", "Pop", "onsale", null,
        null, null, null);
  }

  private static ConcertBrut concertAvecHeure(
      String artiste, String date, String heure, String salle, String source) {
    return new ConcertBrut(
        artiste, date, heure, salle, "Paris", "75", null, source, "music", "Pop", "onsale", null,
        null, null, null);
  }

  private static ConcertBrut concert(String artiste, String date, String salle, String source) {
    return new ConcertBrut(
        artiste,
        date,
        "20:30",
        salle,
        "Paris",
        "75",
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

  private static ConcertBrut concertAvecUrl(
      String artiste, String date, String salle, String source, String url) {
    return new ConcertBrut(
        artiste, date, "20:30", salle, "Paris", "75", url, source, "music", "Pop", "onsale", null,
        null, null, null);
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
  void persisterConcerts_upsertConflitMemeArtisteDateSalleHeure_metsAJourPasInsertion() {
    // Même artiste, date, heure et salle → conflit → mise à jour (pas doublon)
    ConcertBrut v1 = concert("Mika", "2026-07-10", "Olympia", "ticketmaster");
    ConcertBrut v2 =
        new ConcertBrut(
            "Mika",
            "2026-07-10",
            "20:30",
            "Olympia",
            "Paris",
            "75",
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
        List.of(concert("Star Academy", DATE_RECENTE, "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy 1", DATE_RECENTE, "Accor Arena", "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artisteRenommeAvecArticleAjoute_metsAJourPasInsertion() {
    // ticket #619 — "Comédies Musicales" -> "Les Comédies Musicales" entre deux fetches TM
    persisterConcerts.persisterConcerts(
        List.of(
            concert(
                "Comédies Musicales", "2026-11-19", "Cirque D'Hiver Bouglione", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(
            concert(
                "Les Comédies Musicales",
                "2026-11-19",
                "Cirque D'Hiver Bouglione",
                "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artistesDistinctsMemeSalleEtDate_conserveLesDeuxConcerts() {
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink", DATE_RECENTE, "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink Floyd", DATE_RECENTE, "Accor Arena", "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(2L, count);
  }

  @Test
  void persisterConcerts_salleSourceIdConnu_reutiliseSalleId() {
    // Pré-condition : salle déjà en base avec son UUID source TM
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor"
            + " arena')",
        Map.of());
    java.util.UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpaZGFe')",
        Map.of("uuid", salleId.toString()));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Stromae", "2026-10-01", "Accor Arena", "ticketmaster:KovZpaZGFe", null)));

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(salleId, uuidEnBase);
  }

  @Test
  void persisterConcerts_salleRenommee_metAJourNomConserveSalleId() {
    // Scénario Plenitude Arena : même venueId TM, nom différent entre deux fetches
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm)"
            + " VALUES ('paris la defense arena', 'Paris La Défense Arena',"
            + " 'paris la defense arena')",
        Map.of());
    java.util.UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'paris la defense arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpbFGFe')",
        Map.of("uuid", salleId.toString()));

    // Fetch avec le nouveau nom
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Dua Lipa", "2026-11-15", "Plenitude Arena", "ticketmaster:KovZpbFGFe", null)));

    java.util.UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'dua lipa'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(salleId, uuidEnBase, "le salle_id doit être stable après renommage");

    String nomMisAJour =
        jdbc.queryForObject(
            "SELECT nom FROM salles WHERE id = CAST(:uuid AS uuid)",
            Map.of("uuid", salleId.toString()),
            String.class);
    assertEquals("Plenitude Arena", nomMisAJour, "le nom de la salle doit être mis à jour");
  }

  @Test
  void persisterConcerts_reconciliationSalle_conserveSalleId() {
    // ticket #632 — le salleId ne doit pas être effacé lors de la réconciliation salle
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('zenith paris', 'Zénith Paris',"
            + " 'zenith paris')",
        Map.of());
    java.util.UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'zenith paris'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpZenith')",
        Map.of("uuid", salleId.toString()));

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
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'mika'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(
        salleId, uuidEnBase, "le salle_id ne doit pas être perdu lors de la réconciliation");
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
            "SELECT COUNT(*) FROM salles WHERE salle_cle = 'l empreinte'", Map.of(), Long.class);
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
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertNotNull(uuidEnBase, "concerts.salle_id doit être rempli après upsert");
  }

  @Test
  void persisterConcerts_salleAvecIdLegacy_reutiliseSalleExistanteSansDoublon() {
    // Scénario : salle créée avec un id legacy non normalisé (ex. "La Seine Musicale" avec
    // majuscule et espaces). Un fetch TM trouve un venue du même nom → upsertSalleSource
    // doit retrouver la salle par unaccent(lower(nom)) et ne pas créer de doublon.
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm)"
            + " VALUES ('La Seine Musicale', 'La Seine Musicale', 'La Seine Musicale')",
        Map.of());
    java.util.UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'La Seine Musicale'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));

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
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'billie eilish'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
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
  void persisterConcerts_salleSansApostropheAutreSource_reutiliseSalleExistanteSansDoublon() {
    // #902 : TM persiste d'abord "L'Olympia" (nom_norm="l olympia"), puis un cycle Eventim
    // ultérieur rencontre la même salle sous "LOlympia" (sans apostrophe, salleSourceId inconnu
    // de salle_sources — comme un vrai fetch Eventim). Sans le critère squash de
    // CorrespondanceSalle + le préfiltre élargi de chercherSalleParTokens, upsertSalleSource
    // créerait une deuxième salle distincte au lieu de rattacher au salle_id existant.
    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Stromae",
                DATE_RECENTE,
                "20:30",
                "L'Olympia",
                "Paris",
                "75",
                "https://example.com/stromae-tm",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "ticketmaster:KovZOlympia",
                null)));

    java.util.UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'l olympia'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    assertNotNull(uuidOriginal, "la salle TM doit être créée avec la salle_cle normalisée");

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Stromae",
                "2026-11-21",
                "20:30",
                "LOlympia",
                "Paris",
                "75",
                "https://example.com/stromae-eventim",
                "eventim",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "eventim:internal:lolympia",
                null)));

    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(
        1L, nbSalles, "aucune salle dupliquée ne doit être créée pour la variante sans apostrophe");

    java.util.UUID uuidConcertEventim =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae' AND date ="
                + " '2026-11-21'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(
        uuidOriginal,
        uuidConcertEventim,
        "le concert Eventim doit pointer vers la salle TM existante, pas une nouvelle salle");
  }

  @Test
  void persisterConcerts_salleSourceIdNull_rattacheParTokensASalleExistante() {
    // #965 : TM omet parfois venue.id pour un événement donné — sans identifiant source du tout
    // (contrairement à #902 ci-dessus, où un identifiant existait mais restait inconnu de
    // salle_sources). resoudreIdentitesSources doit alors se rabattre sur
    // chercherSalleParTokens plutôt que de laisser salle_id indéfiniment null.
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('l olympia', 'L''Olympia', 'l olympia', '75')",
        Map.of());
    java.util.UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'l olympia'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(concertAvecSourceId("AC/DC", "2026-11-25", "LOlympia", null, null)));

    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "aucune salle dupliquée ne doit être créée sans identifiant source");

    java.util.UUID uuidConcert =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'ac dc'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(
        uuidOriginal, uuidConcert, "le concert sans salleSourceId doit être rattaché par tokens");
  }

  @Test
  void persisterConcerts_salleSourceIdNullSansCorrespondance_salleIdResteNull() {
    // Sans salleSourceId et sans salle existante correspondante, aucune salle fantôme ne doit
    // être créée — salle_id reste null (contrairement à upsertSalleSource, qui crée la salle
    // quand un identifiant source fiable est disponible).
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId("Angele", "2026-11-26", "Salle Totalement Inconnue", null, null)));

    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(0L, nbSalles, "aucune salle ne doit être créée sans identifiant source fiable");

    java.util.UUID salleId =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'angele'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertNull(salleId, "salle_id doit rester null sans correspondance trouvée");
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
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor"
            + " arena')",
        Map.of());
    java.util.UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpAccor')",
        Map.of("uuid", salleId.toString()));

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
            "SELECT nom FROM salles WHERE id = CAST(:uuid AS uuid)",
            Map.of("uuid", salleId.toString()),
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

  @Test
  void persisterConcerts_deuxSeancesHeuresDifferentes_insereDeuxConcerts() {
    // #640 — matinée + soirée du même artiste dans la même salle le même jour doivent coexister
    List<ConcertBrut> liste =
        List.of(
            concertAvecHeure(
                "Tartuffe", "2026-10-10", "14:30", "Comédie-Française", "ticketmaster"),
            concertAvecHeure(
                "Tartuffe", "2026-10-10", "20:00", "Comédie-Française", "ticketmaster"));

    persisterConcerts.persisterConcerts(liste);

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(2L, count, "matinée et soirée doivent être deux concerts distincts");
  }

  @Test
  void persisterConcerts_memeUrlArtistesDifferents_unifieArtisteNorm() {
    // #719 — page festival Eventim : "Yael Naim" (batch H:00) puis "Solidays 2026" (region H:30)
    // ont la même URL source. resoudreParUrl doit forcer l'artiste_norm du 2ème fetch à celui
    // du 1er fetch déjà en base, évitant deux lignes et les notifications répétées.
    String urlFestival =
        "https://www.eventim.fr/event/solidays-2026-hippodrome-de-longchamp-21086080/";

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecUrl(
                "Yael Naim", DATE_RECENTE, "Hippodrome de Longchamp", "eventim", urlFestival)));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecUrl(
                "Solidays 2026", DATE_RECENTE, "Hippodrome de Longchamp", "eventim", urlFestival)));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "les deux concerts avec la même URL doivent fusionner en un seul");

    String artisteEnBase =
        jdbc.queryForObject("SELECT artiste_norm FROM concerts", Map.of(), String.class);
    assertEquals("yael naim", artisteEnBase, "l'artiste_norm du premier fetch doit être conservé");
  }

  @Test
  void persisterConcerts_digitickSansArtisteSourceId_resolutionParNomNorm() {
    // #739 — Digitick ne fournit pas d'artisteSourceId : la résolution doit quand même
    // retrouver l'artiste_id en base via correspondance exacte sur artiste_norm.
    java.util.UUID artisteId = java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", artisteId.toString())
            .addValue("nom", "Mika")
            .addValue("nomNorm", "mika"));

    ConcertBrut concertDigitick =
        new ConcertBrut(
            "Mika",
            "2026-12-10",
            "20:00",
            "Le Zénith Paris",
            "Paris",
            "75",
            "https://web.digitick.com/mika-zenith-paris",
            "digitick",
            null,
            "Concert",
            null,
            null,
            null,
            "digitick:zenith-paris",
            null);

    persisterConcerts.persisterConcerts(List.of(concertDigitick));

    java.util.UUID artisteIdEnBase =
        jdbc.queryForObject(
            "SELECT artiste_id FROM concerts WHERE artiste_norm = 'mika'",
            Map.of(),
            (rs, _) -> rs.getObject("artiste_id", java.util.UUID.class));
    assertEquals(
        artisteId, artisteIdEnBase, "artiste_id doit être résolu par nom_norm pour Digitick");
  }

  @Test
  void persisterConcerts_deuxConcertsSansHeure_fusionne() {
    // #640 — deux enregistrements sans heure pour le même (artiste, date, salle) → 1 seul concert
    List<ConcertBrut> liste =
        List.of(
            concertAvecHeure("Muse", "2026-11-15", null, "Accor Arena", "ticketmaster"),
            concertAvecHeure("Muse", "2026-11-15", null, "Accor Arena", "eventim"));

    persisterConcerts.persisterConcerts(liste);

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(
        1L, count, "deux concerts sans heure du même artiste/date/salle doivent fusionner");
  }

  @Test
  void persisterConcerts_memeArtisteMemeDateSalleNormsDifferentsMemeUuid_neLancePasBadSqlGrammar() {
    // Régression #738/#745 — la contrainte uq_concerts_artiste_date_heure_salle porte sur
    // salle_id depuis V37. TM et Eventim nommant la même salle différemment mais résolvant
    // au même salle_id produisaient un "ON CONFLICT DO UPDATE command cannot affect row a
    // second time" si deduplicerLotReconcilie utilisait salle_norm comme clé.
    java.util.UUID salleId = java.util.UUID.randomUUID();
    String salleCle = "salle-test-" + salleId;
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :salleCle, :nom, :nomNorm, '75')",
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", salleId.toString())
            .addValue("salleCle", salleCle)
            .addValue("nom", "Zénith Paris La Villette")
            .addValue("nomNorm", "zenith paris la villette"));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salleId AS uuid), 'ticketmaster', 'TM_VENUE_001')",
        Map.of("salleId", salleId.toString()));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salleId AS uuid), 'eventim', 'eventim:internal:zenith de paris')",
        Map.of("salleId", salleId.toString()));

    List<ConcertBrut> liste =
        List.of(
            new ConcertBrut(
                "Radiohead",
                "2026-11-20",
                null,
                "Zenith Paris La Villette",
                "Paris",
                "75",
                "https://www.ticketmaster.fr/radiohead",
                "ticketmaster",
                null,
                "Concert",
                null,
                null,
                null,
                "tm:TM_VENUE_001",
                null),
            new ConcertBrut(
                "Radiohead",
                "2026-11-20",
                null,
                "Zenith de Paris",
                "Paris",
                "75",
                "https://www.eventim.fr/radiohead",
                "eventim",
                null,
                "Concert",
                null,
                null,
                null,
                "eventim:internal:zenith de paris",
                null));

    assertDoesNotThrow(
        () -> persisterConcerts.persisterConcerts(liste),
        "persisterConcerts ne doit pas lever BadSqlGrammarException quand deux concerts ont"
            + " le même salle_id mais des salle_norm différents");

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "les deux concerts doivent fusionner en un seul via salle_id");
  }

  // #790 — supprimerDoublonsParSalleId ne couvrait que les paires TM/Eventim codées en dur.
  // Ce test simule un doublon résiduel TM+Digitick (même artiste_id/date/heure/salle_id mais
  // artiste_norm distinct — la contrainte unique porte sur artiste_norm, pas artiste_id, donc les
  // deux lignes peuvent légitimement coexister en base malgré une résolution UUID identique).
  @Test
  void persisterConcerts_doublonResiduelTicketmasterDigitick_supprimeLaLigneDigitick() {
    java.util.UUID artisteId = java.util.UUID.randomUUID();
    java.util.UUID salleId = java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", artisteId.toString())
            .addValue("nom", "Coldplay")
            .addValue("nomNorm", "coldplay"));
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :salleCle, :nom, :nomNorm, '75')",
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", salleId.toString())
            .addValue("salleCle", "salle-test-" + salleId)
            .addValue("nom", "Accor Arena")
            .addValue("nomNorm", "accor arena"));

    String sqlInsertConcert =
        """
        INSERT INTO concerts
            (artiste, artiste_norm, date, heure, salle, salle_norm, source, artiste_id, salle_id)
        VALUES
            (:artiste, :artisteNorm, '2026-12-05', '20:00', :salle, 'accor arena',
             CAST(:source AS source_concert), CAST(:artisteId AS uuid), CAST(:salleId AS uuid))
        """;
    jdbc.update(
        sqlInsertConcert,
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("artiste", "Coldplay")
            .addValue("artisteNorm", "coldplay")
            .addValue("salle", "Accor Arena")
            .addValue("source", "ticketmaster")
            .addValue("artisteId", artisteId.toString())
            .addValue("salleId", salleId.toString()));
    jdbc.update(
        sqlInsertConcert,
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("artiste", "Cold Play")
            .addValue("artisteNorm", "cold play")
            .addValue("salle", "Accor Arena Digitick")
            .addValue("source", "digitick")
            .addValue("artisteId", artisteId.toString())
            .addValue("salleId", salleId.toString()));

    // Un concert sans rapport déclenche le passage de persisterConcerts (et donc le nettoyage
    // supprimerDoublonsParSalleId) sans interagir avec les deux lignes insérées ci-dessus.
    persisterConcerts.persisterConcerts(
        List.of(concert("Artiste Sans Rapport", "2026-12-06", "Autre Salle", "ticketmaster")));

    List<String> sourcesRestantes =
        jdbc.queryForList(
            "SELECT source::text FROM concerts WHERE artiste_id = CAST(:artisteId AS uuid)",
            Map.of("artisteId", artisteId.toString()),
            String.class);
    assertEquals(
        List.of("ticketmaster"),
        sourcesRestantes,
        "la ligne Digitick doit être supprimée au profit de la ligne Ticketmaster (priorité)");
  }
}
