/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/// Tests d'intégration de [PersisterConcerts] avec PostgreSQL réel (Testcontainers).
///
/// @since 2.0.0
/// @author Kwaite
class PersisterConcertsIntegrationTest extends AbstractWorkerIntegrationTest {

  // Date relative à "aujourd'hui" (comme RecalculerMatchesServiceIntegrationTest#DATE_FUTURE) —
  // ReconciliateurArtistes.reconcilierArtistes ne charge que les concerts des 7 derniers jours
  // pour la réconciliation inter-jobs ; une date figée finit toujours par sortir de cette
  // fenêtre au fil du temps (constaté sur "2026-06-28", devenu invalide après le 5 juillet).
  private static final String DATE_RECENTE = LocalDate.now().toString();

  @MockitoBean TicketmasterSource ticketmasterSource;

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
  void persisterConcerts_statutPreventeApresDeuxCyclesEventimSuccessifs_resteProtege() {
    // Eventim envoie systématiquement un statut null (pas de distinction prévente/vente
    // complète côté API) — un premier cycle Eventim change déjà concerts.source, un second
    // cycle Eventim ne doit pas pour autant écraser le statut détecté par TM.
    ConcertBrut viaTm =
        new ConcertBrut(
            "Angele",
            "2026-11-06",
            "20:30",
            "Zenith",
            "Paris",
            "75",
            null,
            "ticketmaster",
            "music",
            "Pop",
            "presale",
            null,
            null,
            null,
            null);
    ConcertBrut viaEventim =
        new ConcertBrut(
            "Angele",
            "2026-11-06",
            "20:30",
            "Zenith",
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
            null);

    persisterConcerts.persisterConcerts(List.of(viaTm));
    persisterConcerts.persisterConcerts(List.of(viaEventim));
    persisterConcerts.persisterConcerts(List.of(viaEventim));

    String statut =
        jdbc.queryForObject(
            "SELECT statut FROM concerts WHERE artiste_norm = 'angele'", Map.of(), String.class);
    assertEquals("presale", statut);
  }

  @Test
  void persisterConcerts_upsertConflitSalleRenommee_metsAJourSalleNormAvecSalle() {
    // salle_norm doit rester cohérent avec salle sur la branche de conflit de l'upsert, pas
    // seulement à l'insertion initiale
    ConcertBrut v1 = concert("Mika", "2026-07-10", "THEATRE HEBERTOT", "ticketmaster");
    ConcertBrut v2 =
        new ConcertBrut(
            "Mika",
            "2026-07-10",
            "20:30",
            "Théâtre du Châtelet",
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

    Map<String, Object> row = jdbc.queryForMap("SELECT salle, salle_norm FROM concerts", Map.of());
    assertEquals("Théâtre du Châtelet", row.get("salle"));
    assertEquals(
        fr.strapontin.core.Normalisation.normaliserTexte("Théâtre du Châtelet"),
        row.get("salle_norm"));
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
    // ticket — "Star Academy" -> "Star Academy 1" entre deux fetches TM, même salle/date
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy", DATE_RECENTE, "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy 1", DATE_RECENTE, "Accor Arena", "ticketmaster")));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artisteRenommeAvecArticleAjoute_metsAJourPasInsertion() {
    // ticket — "Comédies Musicales" -> "Les Comédies Musicales" entre deux fetches TM
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
  void
      persisterConcerts_mappingSalleSourcesIncoherentGeographiquement_ignoreLeMappingEtJournalise() {
    // Un identifiant TM déjà mappé vers une salle d'un autre département que celui du concert
    // entrant doit être écarté plutôt que suivi aveuglément.
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('parc expos quimper', 'Parc Des Expos De Penvillers',"
            + " 'parc des expos de penvillers', '29')",
        Map.of());
    java.util.UUID salleQuimperId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'parc expos quimper'",
            Map.of(),
            (rs, _) -> rs.getObject("id", java.util.UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'rZFSnyZAv1F')",
        Map.of("uuid", salleQuimperId.toString()));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Chimène Badi",
                "2027-12-18",
                "20:30",
                "Parc Des Expositions Brezillet",
                "Saint-Brieuc",
                "22",
                "https://www.ticketmaster.fr/chimene-badi",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "ticketmaster:rZFSnyZAv1F",
                null)));

    java.util.UUID salleEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'chimene badi'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertNotEquals(
        salleQuimperId,
        salleEnBase,
        "le mapping salle_sources incohérent (dept 29) ne doit pas être suivi pour un concert"
            + " dept 22");

    Long nbAvertissements =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE source = 'persistance'"
                + " AND message LIKE '%incohérence géographique%'",
            Map.of(), Long.class);
    assertEquals(1L, nbAvertissements, "l'incohérence doit être journalisée, pas silencieuse");
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
    // ticket — le salleId ne doit pas être effacé lors de la réconciliation salle
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
    // ticket — une salle absente de salle_sources doit être upsertée automatiquement
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
    // TM persiste d'abord "L'Olympia" (nom_norm="l olympia"), puis un cycle Eventim
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
    // TM omet parfois venue.id pour un événement donné — sans identifiant source du tout
    // (contrairement à ci-dessus, où un identifiant existait mais restait inconnu de
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
  void persisterConcerts_nomsDeSalleSansRapportMemeArtisteDateHeureDept_rattacheParCleForte() {
    // Cas réel confirmé en production (Vincent Dedienne/Fréjus, Zazie/Sausheim) : Eventim nomme
    // parfois la salle de façon abrégée/stylisée sans aucun rapport textuel avec le nom TM
    // ("Theatre le Forum Frejus" / "Le Forum - Salle Gounod") — aucun critère textuel ne peut les
    // rapprocher. artiste_norm+date+heure+dept identiques suffit à lui seul.
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Vincent Dedienne",
                "2027-05-23",
                "Le Forum - Salle Gounod",
                "ticketmaster:leforum",
                null)));

    java.util.UUID salleTM =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Vincent Dedienne",
                "2027-05-23",
                "20:30",
                "Theatre le Forum Frejus",
                "Paris",
                "75",
                "https://example.com/dedienne-eventim",
                "eventim",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "eventim:leforumfrejus",
                null)));

    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(
        1L,
        nbSalles,
        "aucune salle dupliquée ne doit être créée malgré l'absence de" + " tout token commun");

    // Même artiste+date+heure+salle_id que la ligne TM déjà en base : le second persist met à
    // jour cette ligne (ON CONFLICT), il n'en insère pas une seconde.
    Long nbConcerts =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbConcerts, "les deux concerts doivent fusionner sur la même ligne");

    java.util.UUID salleFinale =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertEquals(
        salleTM, salleFinale, "la ligne fusionnée doit pointer vers la salle TM existante");
  }

  @Test
  void persisterConcerts_nomsDeSalleSansRapportDeptDifferent_neRattachePasParCleForte() {
    // Même artiste/date/heure mais département différent : la clé forte ne doit pas fusionner —
    // aucune preuve indépendante que c'est le même évènement réel.
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Vincent Dedienne",
                "2027-05-23",
                "Le Forum - Salle Gounod",
                "ticketmaster:leforum2",
                null)));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Vincent Dedienne",
                "2027-05-23",
                "20:30",
                "Theatre le Forum Frejus",
                "Lyon",
                "69",
                "https://example.com/dedienne-eventim-lyon",
                "eventim",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "eventim:leforumfrejus2",
                null)));

    Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles, "deux salles distinctes doivent être créées, dept différent");
  }

  @Test
  void
      persisterConcerts_salleHomonymeDeptNullMaisConcertAttacheDeptDifferent_neFusionnePasParTokens() {
    // salles.dept n'est écrit qu'à la création et jamais mis à jour ensuite, contrairement
    // à concerts.dept (fiablement renseigné à chaque ingestion). Un simple "dept IS NULL OR dept
    // = :dept" désactivait donc le filtre pour la quasi-totalité des salles réelles, laissant
    // fusionner à tort des salles homonymes de villes différentes (ex. Casino Barrière
    // Toulouse/Lille, cas confirmé). Ici : salle existante "Casino Barriere" avec
    // salles.dept NULL, mais un concert déjà rattaché révèle sa vraie localisation (59, Lille).
    java.util.UUID salleLille = java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), 'casino barriere', 'Casino - Barriere',"
            + " 'casino barriere', NULL)",
        Map.of("id", salleLille.toString()));
    jdbc.update(
        "INSERT INTO concerts (artiste, artiste_norm, date, heure, salle, salle_norm, dept,"
            + " source, salle_id)"
            + " VALUES ('Artiste Lille', 'artiste lille', CAST(:date AS date), '20:30',"
            + " 'Casino Barriere', 'casino barriere', '59',"
            + " CAST('ticketmaster' AS source_concert), CAST(:salleId AS uuid))",
        Map.of("date", DATE_RECENTE, "salleId", salleLille.toString()));

    // Nouveau concert homonyme, sans salleSourceId (déclenche chercherSalleParTokens), dept
    // Toulouse (31) — doit être rejeté par le filtre plutôt que fusionné à tort avec Lille.
    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Artiste Toulouse",
                "2026-11-28",
                "20:30",
                "Casino - Barriere",
                "Toulouse",
                "31",
                "https://example.com/artiste-toulouse",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                null,
                null)));

    java.util.UUID salleIdConcertToulouse =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'artiste toulouse'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", java.util.UUID.class));
    assertNotEquals(
        salleLille,
        salleIdConcertToulouse,
        "le concert de Toulouse ne doit pas être rattaché à la salle de Lille malgré"
            + " l'homonymie");
  }

  @Test
  void persisterConcerts_salleExistanteSansDeptNouvelleSourceAvecDept_backfilleLeDept() {
    // salles.dept n'était écrit qu'à la création puis jamais mis à jour — une salle créée
    // sans code postal exploitable restait figée à dept=NULL même quand une source ultérieure
    // apportait l'information (ici : upsertSalleSource, déclenché par un salleSourceId inconnu
    // de salle_sources sur une salle déjà résolue par nom).
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('accor arena', 'Accor Arena', 'accor arena', NULL)",
        Map.of());

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Julien Clerc", "2026-12-01", "Accor Arena", "ticketmaster:venue-aa", null)));

    String dept =
        jdbc.queryForObject(
            "SELECT dept FROM salles WHERE salle_cle = 'accor arena'", Map.of(), String.class);
    assertEquals("75", dept, "le dept du concert doit être propagé à la salle existante sans dept");
  }

  @Test
  void persisterConcerts_salleExistanteAvecDeptNouvelleSourceDeptDifferent_neLEcrasePas() {
    // COALESCE : ne doit jamais écraser une valeur déjà connue, même si une source ultérieure
    // apporte un dept différent (concert erroné, salle multi-adresses...).
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('accor arena', 'Accor Arena', 'accor arena', '75')",
        Map.of());

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Julien Clerc",
                "2026-12-01",
                "20:30",
                "Accor Arena",
                "Lyon",
                "69",
                "https://example.com/julien-clerc",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "ticketmaster:venue-aa",
                null)));

    String dept =
        jdbc.queryForObject(
            "SELECT dept FROM salles WHERE salle_cle = 'accor arena'", Map.of(), String.class);
    assertEquals("75", dept, "un dept déjà connu ne doit jamais être écrasé");
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
    // matinée + soirée du même artiste dans la même salle le même jour doivent coexister
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
    // page festival Eventim : "Yael Naim" (batch H:00) puis "Solidays 2026" (region H:30)
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
  void persisterConcerts_memeUrlTicketmasterArtisteCorrige_laLigneCorrigeeRemplaceLAncienne() {
    // Reproduit le cas réel #1065 : "Holiday On Ice" (spectacle sur glace, classification
    // "divers") corrompu en "Holiday Nice" par une résolution Deezer erronée sur un fetch, avant
    // que la cause racine (résolution Deezer appliquée à tort aux classifications non musicales)
    // ne soit corrigée. Un fetch ultérieur, désormais inéligible à cette résolution, doit pouvoir
    // guérir la ligne déjà persistée plutôt que rester bloqué indéfiniment par les mécanismes de
    // réconciliation (resoudreParUrl, ReconciliateurArtistes) — ni resoudreParUrl (URL partagée)
    // ni la réconciliation floue par date/salle ne doivent forcer l'entrant vers l'ancienne
    // valeur, sans remédiation de données manuelle.
    String urlEvenement = "https://www.ticketmaster.fr/event/holiday-on-ice-123456";

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Holiday Nice",
                DATE_RECENTE,
                "20:30",
                "Zenith Paris - la Villette",
                "Paris",
                "75",
                urlEvenement,
                "ticketmaster",
                "divers",
                null,
                "onsale",
                null,
                null,
                null,
                null)));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Holiday On Ice",
                DATE_RECENTE,
                "20:30",
                "Zenith Paris - la Villette",
                "Paris",
                "75",
                urlEvenement,
                "ticketmaster",
                "divers",
                null,
                "onsale",
                null,
                null,
                null,
                null)));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "l'ancienne ligne corrompue doit être absorbée, pas dupliquée");

    String artisteEnBase =
        jdbc.queryForObject("SELECT artiste_norm FROM concerts", Map.of(), String.class);
    assertEquals(
        "holiday on ice",
        artisteEnBase,
        "la résolution corrigée du second fetch doit remplacer l'ancienne valeur corrompue");
  }

  @Test
  void persisterConcerts_memeUrlTicketmasterHeureReportee_remplaceLAncienneLigne() {
    // Un report Ticketmaster (même URL, heure changée) doit mettre à jour la ligne existante,
    // pas en créer une nouvelle à côté.
    String urlEvenement = "https://www.ticketmaster.fr/event/pussycat-dolls-653083";

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "The Pussycat Dolls",
                DATE_RECENTE,
                "20:00",
                "Accor Arena",
                "Paris",
                "75",
                urlEvenement,
                "ticketmaster",
                "music",
                null,
                "onsale",
                null,
                null,
                null,
                null)));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "The Pussycat Dolls",
                DATE_RECENTE,
                "19:00",
                "Accor Arena",
                "Paris",
                "75",
                urlEvenement,
                "ticketmaster",
                "music",
                null,
                "rescheduled",
                null,
                null,
                null,
                null)));

    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "l'ancienne ligne (20h, en vente) doit être absorbée, pas dupliquée");

    Map<String, Object> ligne =
        jdbc.queryForList("SELECT heure::text, statut::text FROM concerts", Map.of()).get(0);
    assertEquals("19:00:00", ligne.get("heure"), "l'heure reportée doit être conservée");
    assertEquals("rescheduled", ligne.get("statut"), "le statut à jour doit être conservé");
  }

  @Test
  void persisterConcerts_memeUrlTicketmasterMatineeEtSoireeUneAnnulee_neModifiePasLAutre() {
    // Certaines productions (théâtre, cirque) partagent un seul idmanif Ticketmaster pour
    // plusieurs séances le même jour — l'annulation de l'une ne doit jamais toucher l'autre.
    String urlProduction = "https://www.ticketmaster.fr/event/les-miserables-642735";

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Les Misérables",
                DATE_RECENTE,
                "15:00",
                "Zenith Paris",
                "Paris",
                "75",
                urlProduction,
                "ticketmaster",
                "theatre",
                null,
                "onsale",
                null,
                null,
                null,
                null),
            new ConcertBrut(
                "Les Misérables",
                DATE_RECENTE,
                "20:00",
                "Zenith Paris",
                "Paris",
                "75",
                urlProduction,
                "ticketmaster",
                "theatre",
                null,
                "onsale",
                null,
                null,
                null,
                null)));

    // Annulation de la seule séance du soir, même URL/date, heure différente des deux existantes.
    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Les Misérables",
                DATE_RECENTE,
                "20:00",
                "Zenith Paris",
                "Paris",
                "75",
                urlProduction,
                "ticketmaster",
                "theatre",
                null,
                "cancelled",
                null,
                null,
                null,
                null)));

    List<String> heures =
        jdbc.queryForList(
            "SELECT heure::text FROM concerts WHERE url = :url ORDER BY heure",
            Map.of("url", urlProduction),
            String.class);
    assertEquals(
        List.of("15:00:00", "20:00:00"),
        heures,
        "la séance de 15h ne doit pas être altérée par l'annulation ambiguë de celle de 20h");
  }

  @Test
  void persisterConcerts_digitickSansArtisteSourceId_resolutionParNomNorm() {
    // Digitick ne fournit pas d'artisteSourceId : la résolution doit quand même
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
    // deux enregistrements sans heure pour le même (artiste, date, salle) → 1 seul concert
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
    // Régression — la contrainte uq_concerts_artiste_date_heure_salle porte sur
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

  // supprimerDoublonsParSalleId ne couvrait que les paires TM/Eventim codées en dur.
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

  // Une fiche package TM (URL /pack/) référence souvent une heure distincte de la fiche
  // principale du même événement réel — supprimerDoublonsParSalleId doit les fusionner malgré cet
  // écart, en conservant la fiche non-package comme référence.
  @Test
  void persisterConcerts_ficheNormaleEtPackageMemeEvenement_gardeLaFicheNormale() {
    // supprimerDoublonsParSalleId exige artiste_id non nul des deux côtés — résolu par nom
    // uniquement si l'artiste existe déjà (jamais créé automatiquement à la persistance).
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (gen_random_uuid(), 'Jay Z', 'jay z')",
        Map.of());

    List<ConcertBrut> liste =
        List.of(
            new ConcertBrut(
                "Jay Z",
                DATE_RECENTE,
                "19:00",
                "Stade De France",
                "Saint-Denis",
                "93",
                "https://tm.example/jay-z-billet",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "ticketmaster:venue-stade-de-france",
                null),
            new ConcertBrut(
                "Jay Z",
                DATE_RECENTE,
                "20:00",
                "Stade De France",
                "Saint-Denis",
                "93",
                "https://tm.example/pack/jay-z-vip-billet",
                "ticketmaster",
                "music",
                "Pop",
                null,
                null,
                null,
                "ticketmaster:venue-stade-de-france",
                null));

    persisterConcerts.persisterConcerts(liste);

    List<Map<String, Object>> lignes =
        jdbc.queryForList("SELECT url FROM concerts WHERE artiste_norm = 'jay z'", Map.of());
    assertEquals(1, lignes.size(), "les deux fiches doivent fusionner en une seule ligne");
    assertEquals(
        "https://tm.example/jay-z-billet",
        lignes.get(0).get("url"),
        "la fiche non-package doit l'emporter comme référence conservée");
  }

  // Un complexe multi-salles (La Seine Musicale, #1180) ne doit jamais mélanger deux halls
  // physiquement distincts sous un même salle_id — la seconde sous-salle qualifiée rencontrée
  // devient un enfant du complexe plutôt que d'être fusionnée ou de rester ambiguë.
  @Test
  void persisterConcerts_complexeMultiSalles_creeUneSousSalleDistincteEnEnfant() {
    ConcertBrut grandeSeine =
        new ConcertBrut(
            "Artiste Grande Seine",
            DATE_RECENTE,
            "20:30",
            "La Seine Musicale - Grande Seine",
            "Boulogne-Billancourt",
            "92",
            "https://tm.example/grande-seine",
            "ticketmaster",
            "music",
            "Pop",
            "onsale",
            null,
            null,
            "ticketmaster:venue-grande-seine",
            null);
    persisterConcerts.persisterConcerts(List.of(grandeSeine));

    ConcertBrut auditorium =
        new ConcertBrut(
            "Artiste Auditorium",
            DATE_RECENTE,
            "20:30",
            "La Seine Musicale - Auditorium P.devedjian",
            "Boulogne-Billancourt",
            "92",
            "https://tm.example/auditorium",
            "ticketmaster",
            "music",
            "Pop",
            "onsale",
            null,
            null,
            "ticketmaster:venue-auditorium",
            null);
    persisterConcerts.persisterConcerts(List.of(auditorium));

    List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id::text, nom, salle_parent_id::text FROM salles ORDER BY nom", Map.of());
    assertEquals(2, salles.size(), "les deux halls doivent rester deux salles distinctes");

    Map<String, Object> salleGrandeSeine =
        salles.stream()
            .filter(s -> ((String) s.get("nom")).contains("Grande Seine"))
            .findFirst()
            .orElseThrow();
    Map<String, Object> salleAuditorium =
        salles.stream()
            .filter(s -> ((String) s.get("nom")).contains("Auditorium"))
            .findFirst()
            .orElseThrow();

    assertNull(
        salleGrandeSeine.get("salle_parent_id"), "la première salle rencontrée reste le parent");
    assertEquals(
        salleGrandeSeine.get("id"),
        salleAuditorium.get("salle_parent_id"),
        "la seconde sous-salle doit être rattachée à la première comme complexe");

    List<String> sallesDistinctesParConcert =
        jdbc.queryForList("SELECT DISTINCT salle_id::text FROM concerts", Map.of(), String.class);
    assertEquals(
        2,
        sallesDistinctesParConcert.size(),
        "chaque concert doit rester rattaché à son propre hall, jamais confondu avec l'autre");
  }
}
