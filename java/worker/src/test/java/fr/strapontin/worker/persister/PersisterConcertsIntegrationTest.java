/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de [PersisterConcerts] avec PostgreSQL réel (Testcontainers).
 *
 * @since 2.0.0
 * @author Kwaite
 */
class PersisterConcertsIntegrationTest extends AbstractWorkerIntegrationTest {

  // Date relative à "aujourd'hui" (comme RecalculerMatchesServiceIntegrationTest#DATE_FUTURE) —
  // ReconciliateurArtistes.reconcilierArtistes ne charge que les concerts des 7 derniers jours
  // pour la réconciliation inter-jobs ; une date figée finit toujours par sortir de cette
  // fenêtre au fil du temps (constaté sur "2026-06-28", devenu invalide après le 5 juillet).
  private static final String DATE_RECENTE = LocalDate.now().toString();

  @MockitoBean TicketmasterSource ticketmasterSource;
  @MockitoBean ClientNominatim clientNominatim;

  @Autowired private PersisterConcerts persisterConcerts;

  @Autowired private PersisteurConcertsDb persisteurConcertsDb;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    // evenements, pas concerts (vue depuis #1192) — cascade vers observations et user_matches.
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM artiste_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
  }

  private static ConcertBrut concertSansUrl(
      final String artiste, final String date, final String salle, final String source) {
    return new ConcertBrut(
        artiste, date, "20:30", salle, "Paris", "75", null, source, "music", "Pop", "onsale", null,
        null, null, null);
  }

  private static ConcertBrut concertAvecHeure(
      final String artiste,
      final String date,
      final String heure,
      final String salle,
      final String source) {
    return new ConcertBrut(
        artiste, date, heure, salle, "Paris", "75", null, source, "music", "Pop", "onsale", null,
        null, null, null);
  }

  private static ConcertBrut concert(
      final String artiste, final String date, final String salle, final String source) {
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
      final String artiste,
      final String date,
      final String salle,
      final String source,
      final String url) {
    return new ConcertBrut(
        artiste, date, "20:30", salle, "Paris", "75", url, source, "music", "Pop", "onsale", null,
        null, null, null);
  }

  private static ConcertBrut concertAvecSourceId(
      final String artiste,
      final String date,
      final String salle,
      final String salleSourceId,
      final String artisteSourceId) {
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

  /**
   * Seed direct d'un événement + son observation (#1192) — contourne volontairement {@link
   * PersisterConcerts#persisterConcerts} pour reproduire un état déjà en base (résidu, concert
   * existant), équivalent du seed direct {@code INSERT INTO concerts} de l'ancien modèle.
   */
  private UUID seedEvenementAvecObservation(
      final String artiste,
      final String artisteNorm,
      final String date,
      final String heure,
      final String salle,
      final String salleNorm,
      final String dept,
      final UUID salleId,
      final UUID artisteId,
      final String source,
      final String url) {
    final MapSqlParameterSource paramsEvenement =
        new MapSqlParameterSource()
            .addValue("artisteNorm", artisteNorm)
            .addValue("date", date)
            .addValue("heure", heure)
            .addValue("salleNorm", salleNorm)
            .addValue("dept", dept)
            .addValue("salleId", salleId != null ? salleId.toString() : null)
            .addValue("artisteId", artisteId != null ? artisteId.toString() : null);
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, dept, salle_id,"
                + " artiste_id)"
                + " VALUES (:artisteNorm, CAST(:date AS date), CAST(:heure AS time), :salleNorm,"
                + " :dept, CAST(:salleId AS uuid), CAST(:artisteId AS uuid)) RETURNING id",
            paramsEvenement,
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:evenementId AS uuid), CAST(:source AS source_concert), :url,"
            + " :artiste, :salle)",
        new MapSqlParameterSource()
            .addValue("evenementId", evenementId.toString())
            .addValue("source", source)
            .addValue("url", url)
            .addValue("artiste", artiste)
            .addValue("salle", salle));
    return evenementId;
  }

  @Test
  void persisterConcerts_concertValide_insereEnBase() {
    final List<ConcertBrut> liste =
        List.of(concert("Julien Clerc", "2026-09-15", "Accor Arena", "ticketmaster"));

    final PersisterConcerts.ResultatPersistance res = persisterConcerts.persisterConcerts(liste);

    assertEquals(1, res.nbInseres());
    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  private void insererSalleAvecCoordonnees(
      final String nom, final double latitude, final double longitude) {
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, latitude, longitude)"
            + " VALUES (:cle, :nom, :norm, :lat, :lon)",
        new MapSqlParameterSource()
            .addValue("cle", nom.toLowerCase())
            .addValue("nom", nom)
            .addValue("norm", nom.toLowerCase())
            .addValue("lat", latitude)
            .addValue("lon", longitude));
  }

  // upsertSalleSource (donc la résolution GPS-first) n'est atteint que si le concert porte un
  // salleSourceId — sans lui, la résolution reste bornée à un rapprochement par nom déjà connu
  // (raccrocherSallesParNomNorm), jamais une création. Aucun rapport textuel entre "Zenith
  // Historique Inconnu" et "Salle GPS Existante" — seul le géocodage en dernier recours peut les
  // rapprocher.
  @Test
  void persisterConcerts_salleInconnueGeocodageTrouveSalleProche_reutiliseLaSalleExistante() {
    insererSalleAvecCoordonnees("Salle GPS Existante", 48.8566, 2.3522);
    when(clientNominatim.geocoder("Zenith Historique Inconnu, Paris"))
        .thenReturn(Optional.of(new ClientNominatim.Coordonnees(48.8567, 2.3523)));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Artiste GPS",
                "2026-09-20",
                "Zenith Historique Inconnu",
                "ticketmaster:venue-gps-1",
                null)));

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "aucune nouvelle salle ne doit être créée");
    final Long nbLiees =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM concerts WHERE salle_norm = 'zenith historique inconnu'"
                + " AND salle_id = (SELECT id FROM salles WHERE nom = 'Salle GPS Existante')",
            Map.of(),
            Long.class);
    assertEquals(1L, nbLiees);
  }

  @Test
  void persisterConcerts_salleInconnueGeocodageSansSalleProche_creeNouvelleSalleAvecCoordonnees() {
    insererSalleAvecCoordonnees("Salle GPS Lointaine", 43.2965, 5.3698); // Marseille
    when(clientNominatim.geocoder("Zenith Nouveau Lieu, Paris"))
        .thenReturn(Optional.of(new ClientNominatim.Coordonnees(48.8566, 2.3522)));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Artiste GPS 2",
                "2026-09-21",
                "Zenith Nouveau Lieu",
                "ticketmaster:venue-gps-2",
                null)));

    final Map<String, Object> salleCreee =
        jdbc.queryForMap(
            "SELECT latitude, longitude FROM salles WHERE nom = 'Zenith Nouveau Lieu'", Map.of());
    assertEquals(48.8566, (Double) salleCreee.get("latitude"), 0.0001);
    assertEquals(2.3522, (Double) salleCreee.get("longitude"), 0.0001);
  }

  @Test
  void persisterConcerts_salleInconnueGeocodageEchoue_creeNouvelleSalleSansCoordonnees() {
    when(clientNominatim.geocoder("Zenith Sans Geocodage, Paris")).thenReturn(Optional.empty());

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Artiste GPS 3",
                "2026-09-22",
                "Zenith Sans Geocodage",
                "ticketmaster:venue-gps-3",
                null)));

    final Map<String, Object> salleCreee =
        jdbc.queryForMap(
            "SELECT latitude, longitude FROM salles WHERE nom = 'Zenith Sans Geocodage'", Map.of());
    assertNull(salleCreee.get("latitude"));
    assertNull(salleCreee.get("longitude"));
  }

  @Test
  void persisterConcerts_upsertConflitMemeArtisteDateSalleHeure_metsAJourPasInsertion() {
    // Même artiste, date, heure et salle → conflit → mise à jour (pas doublon)
    final ConcertBrut v1 = concert("Mika", "2026-07-10", "Olympia", "ticketmaster");
    final ConcertBrut v2 =
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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_statutPreventeApresDeuxCyclesEventimSuccessifs_resteProtege() {
    // Eventim envoie systématiquement un statut null (pas de distinction prévente/vente
    // complète côté API) — un premier cycle Eventim change déjà concerts.source, un second
    // cycle Eventim ne doit pas pour autant écraser le statut détecté par TM.
    final ConcertBrut viaTm =
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
    final ConcertBrut viaEventim =
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

    final String statut =
        jdbc.queryForObject(
            "SELECT statut FROM concerts WHERE artiste_norm = 'angele'", Map.of(), String.class);
    assertEquals("presale", statut);
  }

  @Test
  void persisterConcerts_upsertConflitSalleRenommee_metsAJourSalleNormAvecSalle() {
    // salle_norm (evenements) doit rester cohérent avec salle (vue concerts, meilleure
    // observation par priorité de source) sur la branche de conflit de l'upsert, pas seulement à
    // l'insertion initiale — Eventim arrive en premier, Ticketmaster (priorité supérieure)
    // en second, devient donc la référence affichée aux deux upserts.
    final ConcertBrut v1 = concert("Mika", "2026-07-10", "THEATRE HEBERTOT", "eventim");
    final ConcertBrut v2 =
        new ConcertBrut(
            "Mika",
            "2026-07-10",
            "20:30",
            "Théâtre du Châtelet",
            "Paris",
            "75",
            "https://example.com/mika-v2",
            "ticketmaster",
            "music",
            "Pop",
            null,
            null,
            null,
            null,
            null);

    persisterConcerts.persisterConcerts(List.of(v1));
    persisterConcerts.persisterConcerts(List.of(v2));

    final Map<String, Object> row =
        jdbc.queryForMap("SELECT salle, salle_norm FROM concerts", Map.of());
    assertEquals("Théâtre du Châtelet", row.get("salle"));
    assertEquals(
        fr.strapontin.core.Normalisation.normaliserTexte("Théâtre du Châtelet"),
        row.get("salle_norm"));
  }

  @Test
  void persisterConcerts_preferenceArtisteDiacritiques_conserveNomAvecAccents() {
    final List<ConcertBrut> liste =
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

    final String artiste =
        jdbc.queryForObject(
            "SELECT artiste FROM concerts WHERE artiste_norm = 'julien dore'",
            Map.of(),
            String.class);
    assertEquals("Julien Doré", artiste);
  }

  @Test
  void persisterConcerts_listeVide_retourneIdsVides() {
    final PersisterConcerts.ResultatPersistance res =
        persisterConcerts.persisterConcerts(List.of());
    assertEquals(0, res.nbInseres() + res.nbMisAJour());
  }

  @Test
  void persisterConcerts_artisteRenommeAvecSuffixeNumerique_metsAJourPasInsertion() {
    // ticket — "Star Academy" -> "Star Academy 1" entre deux fetches TM, même salle/date
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy", DATE_RECENTE, "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Star Academy 1", DATE_RECENTE, "Accor Arena", "ticketmaster")));

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count);
  }

  @Test
  void persisterConcerts_artistesDistinctsMemeSalleEtDate_conserveLesDeuxConcerts() {
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink", DATE_RECENTE, "Accor Arena", "ticketmaster")));
    persisterConcerts.persisterConcerts(
        List.of(concert("Pink Floyd", DATE_RECENTE, "Accor Arena", "ticketmaster")));

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(2L, count);
  }

  @Test
  void persisterConcerts_salleSourceIdConnu_reutiliseSalleId() {
    // Pré-condition : salle déjà en base avec son UUID source TM
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('accor arena', 'Accor Arena', 'accor"
            + " arena')",
        Map.of());
    final UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpaZGFe')",
        Map.of("uuid", salleId.toString()));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Stromae", "2026-10-01", "Accor Arena", "ticketmaster:KovZpaZGFe", null)));

    final UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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
    final UUID salleQuimperId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'parc expos quimper'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
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

    final UUID salleEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'chimene badi'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    assertNotEquals(
        salleQuimperId,
        salleEnBase,
        "le mapping salle_sources incohérent (dept 29) ne doit pas être suivi pour un concert"
            + " dept 22");
    // #1217 : sans salle dept 22 déjà connue pour absorber le repli flou, une nouvelle salle doit
    // être créée plutôt que de laisser salle_id indéfiniment null.
    assertNotNull(salleEnBase, "le concert ne doit pas rester bloqué sans salle_id");

    final Long nbAvertissements =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE source = 'persistance'"
                + " AND message LIKE '%incohérence géographique%'",
            Map.of(), Long.class);
    assertEquals(1L, nbAvertissements, "l'incohérence doit être journalisée, pas silencieuse");
    final UUID salleMapping =
        jdbc.queryForObject(
            "SELECT salle_id FROM salle_sources WHERE identifiant_externe = 'rZFSnyZAv1F'",
            Map.of(),
            UUID.class);
    assertEquals(salleEnBase, salleMapping, "le mapping rejeté est réassigné, plus recontourné");
  }

  @Test
  void persisterConcerts_mappingIncoherentEtSalleCorrecteDejaConnue_laRattache() {
    // Une salle correcte pour le département entrant existe déjà (cas Chimène Badi réel, #1217) —
    // le repli par tokens doit la retrouver sans passer par la création.
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('parc expos quimper', 'Parc Des Expos De Penvillers',"
            + " 'parc des expos de penvillers', '29')",
        Map.of());
    final UUID salleQuimperId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'parc expos quimper'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'rZFSnyZAv1F')",
        Map.of("uuid", salleQuimperId.toString()));
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept)"
            + " VALUES ('parc expos brezillet', 'PARC DES EXPOSITIONS Brezillet ST BRIEUC',"
            + " 'parc des expositions brezillet st brieuc', '22')",
        Map.of());
    final UUID salleBrezilletId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'parc expos brezillet'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Jerome Commandeur",
                "2027-03-03",
                "20:30",
                "Parc Des Expositions Brezillet",
                "Saint-Brieuc",
                "22",
                "https://www.ticketmaster.fr/jerome-commandeur",
                "ticketmaster",
                "music",
                "Pop",
                "onsale",
                null,
                null,
                "ticketmaster:rZFSnyZAv1F",
                null)));

    final UUID salleEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'jerome commandeur'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    assertEquals(salleBrezilletId, salleEnBase);

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles, "aucune salle fantôme créée quand le repli par tokens suffit");
  }

  @Test
  void persisterConcerts_salleRenommee_metAJourNomConserveSalleId() {
    // Scénario Plenitude Arena : même venueId TM, nom différent entre deux fetches
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm)"
            + " VALUES ('paris la defense arena', 'Paris La Défense Arena',"
            + " 'paris la defense arena')",
        Map.of());
    final UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'paris la defense arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpbFGFe')",
        Map.of("uuid", salleId.toString()));

    // Fetch avec le nouveau nom
    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Dua Lipa", "2026-11-15", "Plenitude Arena", "ticketmaster:KovZpbFGFe", null)));

    final UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'dua lipa'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    assertEquals(salleId, uuidEnBase, "le salle_id doit être stable après renommage");

    final String nomMisAJour =
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
    final UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'zenith paris'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "la réconciliation ne doit pas créer un doublon");

    final UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'mika'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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

    final Long nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE salle_cle = 'l empreinte'", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "la salle doit être créée dans salles");

    final Long nbSources =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salle_sources"
                + " WHERE source = 'ticketmaster' AND identifiant_externe = 'KovZpEmpreinte'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbSources, "le mapping source doit être créé dans salle_sources");

    final UUID uuidEnBase =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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
    final UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'La Seine Musicale'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecSourceId(
                "Billie Eilish",
                "2026-11-01",
                "LA SEINE MUSICALE",
                "ticketmaster:rZ7SnyZaA4L",
                null)));

    final Long nbSalles =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE unaccent(lower(nom)) = 'la seine musicale'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbSalles, "aucun doublon ne doit être créé");

    final UUID uuidConcert =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'billie eilish'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    assertEquals(uuidOriginal, uuidConcert, "le concert doit pointer vers la salle originale");

    final Long nbSources =
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

    final UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'l olympia'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
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

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(
        1L, nbSalles, "aucune salle dupliquée ne doit être créée pour la variante sans apostrophe");

    final UUID uuidConcertEventim =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'stromae' AND date ="
                + " '2026-11-21'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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
    final UUID uuidOriginal =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'l olympia'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));

    persisterConcerts.persisterConcerts(
        List.of(concertAvecSourceId("AC/DC", "2026-11-25", "LOlympia", null, null)));

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "aucune salle dupliquée ne doit être créée sans identifiant source");

    final UUID uuidConcert =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'ac dc'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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

    final UUID salleTM =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));

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

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(
        1L,
        nbSalles,
        "aucune salle dupliquée ne doit être créée malgré l'absence de" + " tout token commun");

    // Même artiste+date+heure+salle_id que la ligne TM déjà en base : le second persist met à
    // jour cette ligne (ON CONFLICT), il n'en insère pas une seconde.
    final Long nbConcerts =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            Long.class);
    assertEquals(1L, nbConcerts, "les deux concerts doivent fusionner sur la même ligne");

    final UUID salleFinale =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'vincent dedienne'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
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
    final UUID salleLille = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), 'casino barriere', 'Casino - Barriere',"
            + " 'casino barriere', NULL)",
        Map.of("id", salleLille.toString()));
    seedEvenementAvecObservation(
        "Artiste Lille",
        "artiste lille",
        DATE_RECENTE,
        "20:30",
        "Casino Barriere",
        "casino barriere",
        "59",
        salleLille,
        null,
        "ticketmaster",
        null);

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

    final UUID salleIdConcertToulouse =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'artiste toulouse'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
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

    final String dept =
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

    final String dept =
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

    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(0L, nbSalles, "aucune salle ne doit être créée sans identifiant source fiable");

    final UUID salleId =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'angele'",
            Map.of(),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    assertNull(salleId, "salle_id doit rester null sans correspondance trouvée");
  }

  @Test
  void persisterConcerts_sallesEnChaineTransitive_retourneUnSeulConcert() {
    // "olympia" ≈ "l olympia" ≈ "l olympia paris" — A≈B et B≈C mais A≢C directement.
    // Sans résolution de chaîne : "olympia" → "l olympia" (1 hop) au lieu de "l olympia paris"
    // → doublon. Avec résolution : toutes convergent vers "l olympia paris" → 1 seul concert.
    // URL null pour que supprimerDoublonsParUrl n'interfère pas dans le comptage.
    final List<ConcertBrut> liste =
        List.of(
            concertSansUrl("Mika", "2026-10-01", "Olympia", "ticketmaster"),
            concertSansUrl("Mika", "2026-10-01", "L'Olympia", "ticketmaster"),
            concertSansUrl("Mika", "2026-10-01", "L'Olympia Paris", "ticketmaster"));

    persisterConcerts.persisterConcerts(liste);

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
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
    final UUID salleId =
        jdbc.queryForObject(
            "SELECT id FROM salles WHERE salle_cle = 'accor arena'",
            Map.of(),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'KovZpAccor')",
        Map.of("uuid", salleId.toString()));

    // Bon nom en premier, mauvais nom en dernier → sans fix, "ACCOR ARENA" aurait gagné.
    final List<ConcertBrut> liste =
        List.of(
            concertAvecSourceId(
                "Mika", "2026-10-01", "Accor Aréna", "ticketmaster:KovZpAccor", null),
            concertAvecSourceId(
                "Coldplay", "2026-10-02", "ACCOR ARENA", "ticketmaster:KovZpAccor", null));

    persisterConcerts.persisterConcerts(liste);

    final String nomEnBase =
        jdbc.queryForObject(
            "SELECT nom FROM salles WHERE id = CAST(:uuid AS uuid)",
            Map.of("uuid", salleId.toString()),
            String.class);
    assertEquals("Accor Aréna", nomEnBase, "le nom avec diacritiques doit être préféré");
  }

  @Test
  void persisterConcerts_artisteNormVide_rejetteLeConcert() {
    final ConcertBrut concert =
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

    final PersisterConcerts.ResultatPersistance res =
        persisterConcerts.persisterConcerts(List.of(concert));

    assertEquals(0, res.nbInseres() + res.nbMisAJour());
    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(0L, count);
  }

  @Test
  void persisterConcerts_deuxSeancesHeuresDifferentes_insereDeuxConcerts() {
    // matinée + soirée du même artiste dans la même salle le même jour doivent coexister
    final List<ConcertBrut> liste =
        List.of(
            concertAvecHeure(
                "Tartuffe", "2026-10-10", "14:30", "Comédie-Française", "ticketmaster"),
            concertAvecHeure(
                "Tartuffe", "2026-10-10", "20:00", "Comédie-Française", "ticketmaster"));

    persisterConcerts.persisterConcerts(liste);

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(2L, count, "matinée et soirée doivent être deux concerts distincts");
  }

  @Test
  void persisterConcerts_memeUrlArtistesDifferents_unifieArtisteNorm() {
    // page festival Eventim : "Yael Naim" (batch H:00) puis "Solidays 2026" (region H:30)
    // ont la même URL source. resoudreParUrl doit forcer l'artiste_norm du 2ème fetch à celui
    // du 1er fetch déjà en base, évitant deux lignes et les notifications répétées.
    final String urlFestival =
        "https://www.eventim.fr/event/solidays-2026-hippodrome-de-longchamp-21086080/";

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecUrl(
                "Yael Naim", DATE_RECENTE, "Hippodrome de Longchamp", "eventim", urlFestival)));

    persisterConcerts.persisterConcerts(
        List.of(
            concertAvecUrl(
                "Solidays 2026", DATE_RECENTE, "Hippodrome de Longchamp", "eventim", urlFestival)));

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "les deux concerts avec la même URL doivent fusionner en un seul");

    final String artisteEnBase =
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
    final String urlEvenement = "https://www.ticketmaster.fr/event/holiday-on-ice-123456";

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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "l'ancienne ligne corrompue doit être absorbée, pas dupliquée");

    final String artisteEnBase =
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
    final String urlEvenement = "https://www.ticketmaster.fr/event/pussycat-dolls-653083";

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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "l'ancienne ligne (20h, en vente) doit être absorbée, pas dupliquée");

    final Map<String, Object> ligne =
        jdbc.queryForList("SELECT heure::text, statut::text FROM concerts", Map.of()).get(0);
    assertEquals("19:00:00", ligne.get("heure"), "l'heure reportée doit être conservée");
    assertEquals("rescheduled", ligne.get("statut"), "le statut à jour doit être conservé");
  }

  @Test
  void persisterConcerts_memeUrlTicketmasterMatineeEtSoireeUneAnnulee_neModifiePasLAutre() {
    // Certaines productions (théâtre, cirque) partagent un seul idmanif Ticketmaster pour
    // plusieurs séances le même jour — l'annulation de l'une ne doit jamais toucher l'autre.
    final String urlProduction = "https://www.ticketmaster.fr/event/les-miserables-642735";

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

    final List<String> heures =
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
  void persisterConcerts_sansArtisteSourceId_resolutionParNomNorm() {
    // Un concert Eventim découvert par lieu ou par ville n'a pas d'artisteSourceId : la
    // résolution doit quand même retrouver l'artiste_id en base par artiste_norm.
    final UUID artisteId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        new MapSqlParameterSource()
            .addValue("id", artisteId.toString())
            .addValue("nom", "Mika")
            .addValue("nomNorm", "mika"));

    final ConcertBrut concertSansIdArtiste =
        new ConcertBrut(
            "Mika",
            "2026-12-10",
            "20:00",
            "Le Zénith Paris",
            "Paris",
            "75",
            "https://www.eventim.fr/event/mika-zenith-paris-1",
            "eventim",
            null,
            "Concert",
            null,
            null,
            null,
            "eventim:internal:le zenith paris",
            null);

    persisterConcerts.persisterConcerts(List.of(concertSansIdArtiste));

    final UUID artisteIdEnBase =
        jdbc.queryForObject(
            "SELECT artiste_id FROM concerts WHERE artiste_norm = 'mika'",
            Map.of(),
            (rs, _) -> rs.getObject("artiste_id", UUID.class));
    assertEquals(
        artisteId,
        artisteIdEnBase,
        "artiste_id doit être résolu par nom_norm sans identifiant source");
  }

  @Test
  void persisterConcerts_deuxConcertsSansHeure_fusionne() {
    // deux enregistrements sans heure pour le même (artiste, date, salle) → 1 seul concert
    final List<ConcertBrut> liste =
        List.of(
            concertAvecHeure("Muse", "2026-11-15", null, "Accor Arena", "ticketmaster"),
            concertAvecHeure("Muse", "2026-11-15", null, "Accor Arena", "eventim"));

    persisterConcerts.persisterConcerts(liste);

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(
        1L, count, "deux concerts sans heure du même artiste/date/salle doivent fusionner");
  }

  @Test
  void persisterConcerts_memeArtisteMemeDateSalleNormsDifferentsMemeUuid_neLancePasBadSqlGrammar() {
    // Régression — la contrainte uq_concerts_artiste_date_heure_salle porte sur
    // salle_id depuis V37. TM et Eventim nommant la même salle différemment mais résolvant
    // au même salle_id produisaient un "ON CONFLICT DO UPDATE command cannot affect row a
    // second time" si deduplicerLotReconcilie utilisait salle_norm comme clé.
    final UUID salleId = UUID.randomUUID();
    final String salleCle = "salle-test-" + salleId;
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :salleCle, :nom, :nomNorm, '75')",
        new MapSqlParameterSource()
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

    final List<ConcertBrut> liste =
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

    final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(1L, count, "les deux concerts doivent fusionner en un seul via salle_id");
  }

  // #1192 : même artiste_id/date/heure/salle_id mais artiste_norm distinct (TM/Eventim) doit
  // désormais fusionner en un seul événement au moment de la résolution, sans supprimer aucune des
  // deux observations (contrairement à l'ancien supprimerDoublonsParSalleId, qui ne couvrait que
  // les paires TM/Eventim codées en dur et perdait la ligne perdante).
  @Test
  void
      upsertLot_memeArtisteIdSalleIdDateHeureArtisteNormDistinct_fusionneSansPerdreLesObservations() {
    final UUID artisteId = UUID.randomUUID();
    final UUID salleId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        new MapSqlParameterSource()
            .addValue("id", artisteId.toString())
            .addValue("nom", "Coldplay")
            .addValue("nomNorm", "coldplay"));
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :salleCle, :nom, :nomNorm, '75')",
        new MapSqlParameterSource()
            .addValue("id", salleId.toString())
            .addValue("salleCle", "salle-test-" + salleId)
            .addValue("nom", "Accor Arena")
            .addValue("nomNorm", "accor arena"));

    final LocalDate date = LocalDate.parse("2026-12-05");
    final LocalTime heure = LocalTime.parse("20:00");
    final ValeurConcert viaTm =
        new ValeurConcert(
            "Coldplay",
            "coldplay",
            date,
            heure,
            "Accor Arena",
            "accor arena",
            "Paris",
            "75",
            null,
            "ticketmaster",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            salleId,
            artisteId);
    final ValeurConcert viaEventim =
        new ValeurConcert(
            "Cold Play",
            "cold play",
            date,
            heure,
            "Accor Arena Eventim",
            "accor arena eventim",
            "Paris",
            "75",
            null,
            "eventim",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            salleId,
            artisteId);

    persisteurConcertsDb.upsertLot(List.of(viaTm, viaEventim));

    final Long nbEvenements =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM evenements WHERE artiste_id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            Long.class);
    assertEquals(1L, nbEvenements, "un seul événement malgré artiste_norm distinct");
    final Long nbObservations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM observations o JOIN evenements e ON e.id = o.evenement_id"
                + " WHERE e.artiste_id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            Long.class);
    assertEquals(2L, nbObservations, "les deux observations sont conservées, aucune supprimée");
    final String source =
        jdbc.queryForObject(
            "SELECT source::text FROM concerts WHERE artiste_id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            String.class);
    assertEquals(
        "ticketmaster", source, "priorité Ticketmaster sur Eventim pour la référence affichée");
  }

  // Une fiche package TM (URL /pack/) référence souvent une heure distincte de la fiche
  // principale du même événement réel — la résolution d'événement (#1192) doit les fusionner
  // malgré cet écart, la fiche non-package servant de référence affichée sans que la fiche
  // package soit perdue (contrairement à l'ancien supprimerDoublonsParSalleId, qui la supprimait).
  @Test
  void persisterConcerts_ficheNormaleEtPackageMemeEvenement_gardeLesDeuxObservations() {
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (gen_random_uuid(), 'Jay Z', 'jay z')",
        Map.of());

    final List<ConcertBrut> liste =
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

    final List<Map<String, Object>> lignes =
        jdbc.queryForList("SELECT url FROM concerts WHERE artiste_norm = 'jay z'", Map.of());
    assertEquals(1, lignes.size(), "les deux fiches doivent fusionner en un seul événement");
    assertEquals(
        "https://tm.example/jay-z-billet",
        lignes.get(0).get("url"),
        "la fiche non-package doit l'emporter comme référence affichée");

    final Long nbObservations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM observations o"
                + " JOIN evenements e ON e.id = o.evenement_id WHERE e.artiste_norm = 'jay z'",
            Map.of(),
            Long.class);
    assertEquals(2L, nbObservations, "la fiche package doit être conservée, pas supprimée");
  }

  // Cas réel confirmé en production : un artiste que personne ne suit par nom (aucune ligne
  // artistes créée, match uniquement via la salle suivie) reste artiste_id NULL indéfiniment —
  // supprimerDoublonsParSalleId (qui exige artiste_id non nul) ne peut alors jamais fusionner ses
  // doublons ; supprimerDoublonsSansArtisteId doit prendre le relais en comparant artiste_norm.
  @Test
  void persisterConcerts_artisteNonSuiviCoTeteAffiche_fusionneSansArtisteId() {
    final List<ConcertBrut> liste =
        List.of(
            new ConcertBrut(
                "Earl Sweatshirt",
                DATE_RECENTE,
                "20:00",
                "Elysee Montmartre",
                "Paris",
                "75",
                "https://tm.example/earl-sweatshirt-billet",
                "ticketmaster",
                "music",
                "Rap",
                "onsale",
                null,
                null,
                "ticketmaster:venue-elysee-montmartre",
                null),
            new ConcertBrut(
                "Earl Sweatshirt & Mike",
                DATE_RECENTE,
                "20:00",
                "Elysee Montmartre",
                "Paris",
                "75",
                "https://www.eventim.fr/earl-sweatshirt-mike-billet",
                "eventim",
                "music",
                "Rap",
                null,
                null,
                null,
                "ticketmaster:venue-elysee-montmartre",
                null));

    persisterConcerts.persisterConcerts(liste);

    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT url, artiste_id FROM concerts WHERE artiste_norm LIKE 'earl sweatshirt%'",
            Map.of());
    assertEquals(1, lignes.size(), "les deux fiches doivent fusionner malgré artiste_id NULL");
    assertNull(
        lignes.get(0).get("artiste_id"), "aucun utilisateur ne suit cet artiste par nom ici");
  }

  // Même cas que ci-dessus mais réparti sur deux cycles de fetch distincts (deux appels
  // persisterConcerts séparés, comme en production réelle où TM et Eventim ne sont jamais fetchés
  // à l'instant près) — la réconciliation intra-lot ne peut alors pas absorber le doublon,
  // seul supprimerDoublonsSansArtisteId (cross-cycle, contre la base) le peut.
  @Test
  void persisterConcerts_artisteNonSuiviDeuxCyclesDeFetchDistincts_fusionneSansArtisteId() {
    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Earl Sweatshirt",
                DATE_RECENTE,
                "20:00",
                "Elysee Montmartre",
                "Paris",
                "75",
                "https://tm.example/earl-sweatshirt-billet",
                "ticketmaster",
                "music",
                "Rap",
                "onsale",
                null,
                null,
                "ticketmaster:venue-elysee-montmartre-2",
                null)));

    persisterConcerts.persisterConcerts(
        List.of(
            new ConcertBrut(
                "Earl Sweatshirt & Mike",
                DATE_RECENTE,
                "20:00",
                "Elysee Montmartre",
                "Paris",
                "75",
                "https://www.eventim.fr/earl-sweatshirt-mike-billet-2",
                "eventim",
                "music",
                "Rap",
                null,
                null,
                null,
                "ticketmaster:venue-elysee-montmartre-2",
                null)));

    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT url FROM concerts WHERE artiste_norm LIKE 'earl sweatshirt%'", Map.of());
    assertEquals(
        1,
        lignes.size(),
        "le second cycle doit fusionner avec la ligne déjà persistée au premier cycle");
  }

  // Un complexe multi-salles (La Seine Musicale, #1180) ne doit jamais mélanger deux halls
  // physiquement distincts sous un même salle_id — la seconde sous-salle qualifiée rencontrée
  // devient un enfant du complexe plutôt que d'être fusionnée ou de rester ambiguë.
  @Test
  void persisterConcerts_complexeMultiSalles_creeUneSousSalleDistincteEnEnfant() {
    final ConcertBrut grandeSeine =
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

    final ConcertBrut auditorium =
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

    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id::text, nom, salle_parent_id::text FROM salles ORDER BY nom", Map.of());
    assertEquals(2, salles.size(), "les deux halls doivent rester deux salles distinctes");

    final Map<String, Object> salleGrandeSeine =
        salles.stream()
            .filter(s -> ((String) s.get("nom")).contains("Grande Seine"))
            .findFirst()
            .orElseThrow();
    final Map<String, Object> salleAuditorium =
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

    final List<String> sallesDistinctesParConcert =
        jdbc.queryForList("SELECT DISTINCT salle_id::text FROM concerts", Map.of(), String.class);
    assertEquals(
        2,
        sallesDistinctesParConcert.size(),
        "chaque concert doit rester rattaché à son propre hall, jamais confondu avec l'autre");
  }

  private static ConcertBrut concertDansDept(
      final String artiste,
      final String salle,
      final String ville,
      final String dept,
      final String salleSourceId) {
    return new ConcertBrut(
        artiste,
        "2026-10-31",
        "20:00",
        salle,
        ville,
        dept,
        "https://example.com/" + artiste.toLowerCase().replace(" ", "-") + "-" + dept,
        "ticketmaster",
        "music",
        "Pop",
        "onsale",
        null,
        null,
        salleSourceId,
        null);
  }

  private UUID insererSalleDansDept(final String nom, final String dept) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept) VALUES (:cle, :nom, :norm, :dept)"
            + " RETURNING id",
        new MapSqlParameterSource()
            .addValue("cle", Normalisation.normaliserTexte(nom))
            .addValue("nom", nom)
            .addValue("norm", Normalisation.normaliserTexte(nom))
            .addValue("dept", dept),
        UUID.class);
  }

  // Des dizaines de salles distinctes partagent un nom générique : un concert à Perpignan ne
  // doit jamais être rattaché au "Palais des Congres" homonyme de Vittel déjà en base, y compris
  // quand la clé naturelle (unique) de ce nom est déjà prise.
  @Test
  void persisterConcerts_nomGeneriqueHomonymeAutreDept_creeUneSalleDistincte() {
    final UUID vittel = insererSalleDansDept("Palais des Congres", "88");
    when(clientNominatim.geocoder("Palais des Congres, Perpignan")).thenReturn(Optional.empty());

    persisterConcerts.persisterConcerts(
        List.of(
            concertDansDept(
                "Tina Arena", "Palais des Congres", "Perpignan", "66", "ticketmaster:venue-66")));

    final Map<String, Object> salle =
        jdbc.queryForMap(
            "SELECT s.id, s.dept FROM concerts c JOIN salles s ON s.id = c.salle_id"
                + " WHERE c.artiste_norm = 'tina arena'",
            Map.of());
    assertNotEquals(vittel, salle.get("id"), "jamais la salle homonyme d'un autre département");
    assertEquals("66", salle.get("dept"));
  }

  // Un mapping salle_sources rejeté pour incohérence géographique était recontourné à chaque
  // cycle sans jamais être corrigé : il doit être réassigné à la salle cohérente trouvée.
  @Test
  void persisterConcerts_mappingSalleSourcesIncoherent_estReassigneALaSalleCoherente() {
    final UUID vittel = insererSalleDansDept("Palais des Congres", "88");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', 'venue-perpignan')",
        Map.of("id", vittel.toString()));
    when(clientNominatim.geocoder("Palais des Congres, Perpignan")).thenReturn(Optional.empty());

    persisterConcerts.persisterConcerts(
        List.of(
            concertDansDept(
                "Tina Arena",
                "Palais des Congres",
                "Perpignan",
                "66",
                "ticketmaster:venue-perpignan")));

    final UUID salleMapping =
        jdbc.queryForObject(
            "SELECT salle_id FROM salle_sources WHERE identifiant_externe = 'venue-perpignan'",
            Map.of(),
            UUID.class);
    assertNotEquals(vittel, salleMapping, "le mapping incohérent doit être réassigné");
    final UUID salleConcert =
        jdbc.queryForObject(
            "SELECT salle_id FROM concerts WHERE artiste_norm = 'tina arena'",
            Map.of(),
            UUID.class);
    assertEquals(salleMapping, salleConcert);
  }
}
