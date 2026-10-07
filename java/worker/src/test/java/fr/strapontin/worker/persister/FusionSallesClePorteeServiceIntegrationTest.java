/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.*;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de [FusionSallesClePorteeService] avec PostgreSQL réel (Testcontainers).
 *
 * @since 2.8.4
 * @author Kwaite
 */
class FusionSallesClePorteeServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  private static final String DATE_FUTURE = LocalDate.now().plusMonths(2).toString();

  // TICKETMASTER_API_KEY n'est pas seedée en base de test — sans ce mock, la construction du
  // contexte Spring échoue dès qu'une autre classe du contexte dépend de TicketmasterSource
  // (WorkerFetchService), même sans rapport avec ce test (même motif que
  // PersisterConcertsIntegrationTest).
  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private FusionSallesClePorteeService fusionSallesClePortee;

  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
    jdbc.update("DELETE FROM signalements_matching", Map.of());
  }

  private UUID creerSalle(final String nom, final String dept, final String wikidataId) {
    return creerSalle(nom, dept, wikidataId, null, null);
  }

  private static final double LAT = 45.8336;
  private static final double LON = 1.2611;

  private UUID creerSalle(
      final String nom,
      final String dept,
      final String wikidataId,
      final Double latitude,
      final Double longitude) {
    final UUID id = UUID.randomUUID();
    // wikidataId/latitude/longitude peuvent être null (Map.of rejette les valeurs nulles) —
    // MapSqlParameterSource accepte les valeurs nulles sans lever d'exception.
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept, wikidata_id, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), :cle, :nom, :nomNorm, :dept, :wikidataId, :latitude,"
            + " :longitude)",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("cle", nom.toLowerCase())
            .addValue("nom", nom)
            .addValue("nomNorm", nom.toLowerCase())
            .addValue("dept", dept)
            .addValue("wikidataId", wikidataId)
            .addValue("latitude", latitude)
            .addValue("longitude", longitude));
    return id;
  }

  private void creerConcert(
      final String artisteNorm, final String heure, final String dept, final UUID salleId) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, dept, salle_id)"
                + " VALUES (:artisteNorm, CAST(:date AS date), CAST(:heure AS time), 'salle',"
                + " :dept, CAST(:salleId AS uuid)) RETURNING id",
            new MapSqlParameterSource()
                .addValue("artisteNorm", artisteNorm)
                .addValue("date", DATE_FUTURE)
                .addValue("heure", heure)
                .addValue("dept", dept)
                .addValue("salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :artiste,"
            + " 'Salle')",
        Map.of("id", evenementId.toString(), "artiste", artisteNorm));
  }

  @Test
  void fusionner_deuxSallesEtabliesPartageantUnConcert_fusionneEtPreserveWikidataId() {
    // Cas réel documenté (#1099) : "Grand Theatre Limoges" (Eventim, sans wikidata_id) et
    // "Opera de Limoges" (TM, wikidata_id connu) désignent le même bâtiment — aucun rapport
    // textuel entre les deux noms, seul un concert partagé (artiste+date+heure+dept) le révèle.
    final UUID grandTheatre = creerSalle("Grand Theatre Limoges", "87", null, LAT, LON);
    final UUID operaLimoges = creerSalle("Opera de Limoges", "87", "Q3354551", LAT, LON);
    creerConcert("laurent voulzy", "20:30", "87", grandTheatre);
    creerConcert("laurent voulzy", "20:30", "87", operaLimoges);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(1, fusions);
    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(1L, nbSalles, "Une seule salle doit subsister après fusion");
    final Long nbConcerts =
        jdbc.queryForObject("SELECT COUNT(*) FROM concerts", Map.of(), Long.class);
    assertEquals(
        1L, nbConcerts, "Les deux concerts partageant la même clé forte doivent être dédupliqués");
    final String wikidataId =
        jdbc.queryForObject("SELECT wikidata_id FROM salles", Map.of(), String.class);
    assertEquals(
        "Q3354551", wikidataId, "Le wikidata_id déjà connu doit être préservé après fusion");
  }

  private void creerLieu(final UUID salleId, final String source, final String identifiant) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salle AS uuid), :source, :identifiant)",
        Map.of("salle", salleId.toString(), "source", source, "identifiant", identifiant));
  }

  // Cas réel : la fusion d'une église dans le théâtre de l'Île Saint-Louis avait supprimé un
  // lieu Eventim du théâtre, la salle absorbée en ayant elle aussi un.
  @Test
  void fusionner_lieuxDeMemeSourceSurLesDeuxSalles_conserveTousLesLieux() {
    final UUID theatre = creerSalle("Theatre Ile Saint Louis", "75", null, LAT, LON);
    final UUID doublon = creerSalle("Theatre Ile Saint-Louis", "75", null, LAT, LON);
    creerLieu(theatre, "eventim", "internal:theatre de lile saint louis paul rey");
    creerLieu(theatre, "eventim", "internal:theatre de lile saint louis@75004");
    creerLieu(doublon, "eventim", "internal:theatre ile saint louis");
    creerLieu(doublon, "ticketmaster", "rZFSnyZAvvk");
    creerConcert("suzane", "20:00", "75", theatre);
    creerConcert("suzane", "20:00", "75", doublon);

    assertEquals(1, fusionSallesClePortee.fusionner());

    assertEquals(
        4L,
        jdbc.queryForObject("SELECT COUNT(*) FROM salle_sources", Map.of(), Long.class),
        "Aucun lieu ne doit disparaître à la fusion");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE message LIKE 'lieux reportés%'",
            Map.of(), Long.class));
  }

  @Test
  void fusionnerMemesLieux_memeDepartement_fusionneEtJournalise() {
    final UUID europeen = creerSalle("L'Européen", "75", null, LAT, LON);
    final UUID doublon = creerSalle("Leuropeen Paris 17", "75", null, LAT, LON);

    assertEquals(
        1,
        fusionSallesClePortee.fusionnerMemesLieux(List.<UUID[]>of(new UUID[] {europeen, doublon})));
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE message LIKE '%même lieu (position et nom)%'",
            Map.of(), Long.class));
  }

  @Test
  void fusionnerMemesLieux_departementsDifferents_neFusionnePas() {
    final UUID a = creerSalle("Salle A", "75", null, LAT, LON);
    final UUID b = creerSalle("Salle A Bis", "92", null, LAT, LON);

    assertEquals(0, fusionSallesClePortee.fusionnerMemesLieux(List.<UUID[]>of(new UUID[] {a, b})));
  }

  @Test
  void fusionner_fusionEffectuee_journaliseDansLogsAdmin() {
    // Une fusion automatique et destructive ne doit jamais rester silencieuse.
    final UUID a = creerSalle("Grand Theatre Limoges", "87", null, LAT, LON);
    final UUID b = creerSalle("Opera de Limoges", "87", "Q3354551", LAT, LON);
    creerConcert("laurent voulzy", "20:30", "87", a);
    creerConcert("laurent voulzy", "20:30", "87", b);

    fusionSallesClePortee.fusionner();

    final Long nbLogs =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE source = 'reconciliation-nocturne' AND"
                + " message LIKE '%fusionnées%'",
            Map.of(), Long.class);
    assertEquals(1L, nbLogs);
    // La salle absorbée est supprimée : seul le journal permet encore de l'identifier.
    final String nomSource =
        jdbc.queryForObject(
            "SELECT meta->>'nomSource' FROM logs_admin WHERE message LIKE '%fusionnées%'",
            Map.of(), String.class);
    assertEquals("Grand Theatre Limoges (87)", nomSource);
  }

  @Test
  void fusionner_aucuneSalleAvecConcertPartage_neFusionneRien() {
    creerSalle("Salle A", "87", null);
    creerSalle("Salle B", "87", null);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(0, fusions);
    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  // Un seul concert coïncidant par erreur ne doit jamais suffire à fusionner deux salles
  // réellement distinctes.
  @Test
  void fusionner_sallesReellementDistinctesConcertCoincident_neFusionnePas() {
    final UUID arena = creerSalle("Arena du Pays d'Aix", "13", null);
    final UUID pasino = creerSalle("Pasino Grand Aix-en-Provence", "13", null);
    creerConcert("collision upstream", "21:00", "13", arena);
    creerConcert("collision upstream", "21:00", "13", pasino);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(0, fusions, "des noms sans rapport ne doivent pas fusionner malgré la clé forte");
    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  // Garde-fou GPS (#1177/#1180) : un nom similaire et une clé forte partagée ne doivent jamais
  // suffire si les coordonnées connues placent les deux salles à plusieurs centaines de mètres
  // l'une de l'autre (complexe multi-salles, ou deux bâtiments distincts par coïncidence).
  @Test
  void fusionner_coordonneesEloigneesNomsSimilaires_neFusionnePas() {
    final UUID salleParis = creerSalle("La Seine Musicale", "92", null, 48.8296, 2.2404);
    final UUID salleMarseille =
        creerSalle("La Seine Musicale Marseille", "13", null, 43.2965, 5.3698);
    creerConcert("collision upstream", "21:00", "92", salleParis);
    creerConcert("collision upstream", "21:00", "13", salleMarseille);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(
        0,
        fusions,
        "des coordonnées distantes doivent bloquer la fusion malgré la clé forte et le nom");
    final Long nbSalles = jdbc.queryForObject("SELECT COUNT(*) FROM salles", Map.of(), Long.class);
    assertEquals(2L, nbSalles);
  }

  @Test
  void fusionner_coordonneesProchesNomsSimilaires_fusionne() {
    final UUID salleA = creerSalle("Grand Theatre Limoges", "87", null, 45.8336, 1.2611);
    final UUID salleB = creerSalle("Opera de Limoges", "87", "Q3354551", 45.8337, 1.2612);
    creerConcert("laurent voulzy", "20:30", "87", salleA);
    creerConcert("laurent voulzy", "20:30", "87", salleB);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(1, fusions, "des coordonnées proches ne doivent pas bloquer une fusion légitime");
  }

  // Une salle fusionnée (source) peut déjà porter des sous-salles — leur salle_parent_id doit
  // suivre vers la cible, sinon la suppression de la source échoue (contrainte FK) ou les laisse
  // orphelines.
  @Test
  void fusionner_sourceAvecSousSalle_reassigneLeParentIdVersLaCible() {
    final UUID grandTheatre = creerSalle("Grand Theatre Limoges", "87", null, LAT, LON);
    final UUID operaLimoges = creerSalle("Opera de Limoges", "87", "Q3354551", LAT, LON);
    final UUID sousSalleDeLaSource = creerSalle("Grand Theatre Limoges - Petite Salle", "87", null);
    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:parentId AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("parentId", grandTheatre.toString(), "id", sousSalleDeLaSource.toString()));
    creerConcert("laurent voulzy", "20:30", "87", grandTheatre);
    creerConcert("laurent voulzy", "20:30", "87", operaLimoges);

    final int fusions = fusionSallesClePortee.fusionner();

    assertEquals(1, fusions);
    final String parentIdApres =
        jdbc.queryForObject(
            "SELECT salle_parent_id::text FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", sousSalleDeLaSource.toString()),
            String.class);
    assertEquals(
        operaLimoges.toString(),
        parentIdApres,
        "la sous-salle doit suivre vers la salle cible conservée après fusion");
  }

  // #1201 : réassigner en bloc tous les enfants de la source vers la cible peut créer un cycle
  // à deux salles quand la cible est déjà une descendante (via un intermédiaire) de la source —
  // cible -> intermediaire -> source deviendrait intermediaire -> cible -> intermediaire.
  @Test
  void fusionner_cibleDescendanteDeLaSourceViaIntermediaire_detacheSansCreerDeCycle() {
    final UUID source = creerSalle("Grand Theatre Limoges", "87", null);
    final UUID intermediaire = creerSalle("Grand Theatre Limoges - Aile Est", "87", null);
    final UUID cible = creerSalle("Grand Theatre Limoges - Aile Est - Loge", "87", null);
    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:parentId AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("parentId", source.toString(), "id", intermediaire.toString()));
    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:parentId AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("parentId", intermediaire.toString(), "id", cible.toString()));

    fusionSallesClePortee.fusionnerUnePaire(cible, source);

    final String parentIntermediaire =
        jdbc.queryForObject(
            "SELECT salle_parent_id::text FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", intermediaire.toString()),
            String.class);
    assertNull(parentIntermediaire, "l'intermédiaire doit être détaché plutôt que créer un cycle");
    final Long nbLogsCycle =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM logs_admin WHERE message LIKE '%cycle salle_parent_id évité%'",
            Map.of(), Long.class);
    assertEquals(1L, nbLogsCycle);
  }

  // Homonymes de villes différentes sans coordonnées (« Palais des Congrès » de Vittel et de
  // Perpignan), réunis par une fiche d'événement mal rattachée : aucune preuve de lieu commun.
  @Test
  void fusionner_homonymesSansCoordonnees_neFusionnePas() {
    final UUID vittel = creerSalle("Palais des Congres", "88", null);
    final UUID perpignan = creerSalle("Palais des Congres Perpignan", "88", null);
    creerConcert("tina arena", "20:00", "88", vittel);
    creerConcert("tina arena", "20:00", "88", perpignan);

    assertEquals(0, fusionSallesClePortee.fusionner());
  }

  // Deux halls d'un même complexe, à la même adresse : le qualificatif de sous-salle diverge.
  @Test
  void fusionner_sousSallesDistinctesDUnComplexe_neFusionnePas() {
    final UUID grandeSeine = creerSalle("La Seine Musicale - Grande Seine", "92", null, LAT, LON);
    final UUID auditorium =
        creerSalle("La Seine Musicale - Auditorium P.devedjian", "92", null, LAT, LON);
    creerConcert("collision", "20:00", "92", grandeSeine);
    creerConcert("collision", "20:00", "92", auditorium);

    assertEquals(0, fusionSallesClePortee.fusionner());
  }

  @Test
  void fusionner_paireDeclareeDistincteParUnAdmin_neFusionnePas() {
    final UUID a = creerSalle("Grand Theatre Limoges", "87", null, LAT, LON);
    final UUID b = creerSalle("Opera de Limoges", "87", null, LAT, LON);
    creerConcert("laurent voulzy", "20:30", "87", a);
    creerConcert("laurent voulzy", "20:30", "87", b);
    jdbc.update(
        "INSERT INTO salles_paires_distinctes (salle_a, salle_b)"
            + " VALUES (LEAST(CAST(:a AS uuid), CAST(:b AS uuid)),"
            + " GREATEST(CAST(:a AS uuid), CAST(:b AS uuid)))",
        Map.of("a", a.toString(), "b", b.toString()));

    assertEquals(0, fusionSallesClePortee.fusionner());
  }

  // Un concert partagé avec une salle mélangeant plusieurs lieux ne prouve pas le même lieu.
  @Test
  void fusionner_salleSignaleeContaminee_neFusionnePas() {
    final UUID grandTheatre = creerSalle("Grand Theatre Limoges", "87", null, LAT, LON);
    final UUID operaLimoges = creerSalle("Opera de Limoges", "87", "Q3354551", LAT, LON);
    creerConcert("laurent voulzy", "20:30", "87", grandTheatre);
    creerConcert("laurent voulzy", "20:30", "87", operaLimoges);
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance)"
            + " VALUES ('dispersion_sources_salle', 'salle', CAST(:id AS uuid), 'haute')",
        Map.of("id", operaLimoges.toString()));

    assertEquals(0, fusionSallesClePortee.fusionner());
  }
}
