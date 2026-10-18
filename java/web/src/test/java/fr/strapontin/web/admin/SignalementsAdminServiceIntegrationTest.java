/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Test d'intégration de {@link SignalementsAdminService} avec PostgreSQL réel (Testcontainers) —
 * la requête UNION ALL sur 4 tables distinctes n'est pas exerçable avec un jdbc mocké.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementsAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private SignalementsAdminService service;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM artiste_sources", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
    jdbc.update(
        "UPDATE salles SET contamination_signalee = NULL, doublon_signale = NULL", Map.of());
    service = new SignalementsAdminService(jdbc, new LogAdminService(jdbc, new ObjectMapper()));
  }

  private UUID creerArtiste(final String nom) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO artistes (id, nom, nom_norm) VALUES (CAST(:id AS uuid), :nom, :nomNorm)",
        Map.of("id", id.toString(), "nom", nom, "nomNorm", nom.toLowerCase()));
    return id;
  }

  @Test
  void lister_signalementNouveauEtMecanismesEtablis_lesAgregeTous() {
    final UUID artisteCollision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance)"
            + " VALUES ('collision_wikidata_artiste', 'artiste', CAST(:id AS uuid), 'basse')",
        Map.of("id", artisteCollision.toString()));

    final UUID artisteIntervention = creerArtiste("Jain");
    jdbc.update(
        "INSERT INTO artiste_sources (artiste_id, source, identifiant_externe,"
            + " necessite_intervention_le) VALUES (CAST(:id AS uuid), 'ticketmaster', 'tm1',"
            + " now())",
        Map.of("id", artisteIntervention.toString()));

    final UUID salleContaminee =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, contamination_signalee)"
                + " VALUES ('zenith-test', 'Zenith Test', 'zenith test', 'autre nom')"
                + " RETURNING id",
            Map.of(),
            UUID.class);

    final ReponseSignalements reponse = service.lister(0);

    assertEquals(3, reponse.signalements().size());
    assertEquals(3L, reponse.total());
    assertTrue(
        reponse.signalements().stream()
            .anyMatch(
                e ->
                    "nouveau".equals(e.categorie())
                        && artisteCollision.toString().equals(e.entite().id())));
    assertTrue(
        reponse.signalements().stream()
            .anyMatch(
                e ->
                    "necessite_intervention".equals(e.categorie())
                        && artisteIntervention.toString().equals(e.entite().id())
                        && "Jain".equals(e.entite().nom())));
    assertTrue(
        reponse.signalements().stream()
            .anyMatch(
                e ->
                    "contamination_salle".equals(e.categorie())
                        && salleContaminee.toString().equals(e.entite().id())
                        && "Zenith Test".equals(e.entite().nom())));
    assertTrue(reponse.catalogue().stream().anyMatch(t -> "contamination_salle".equals(t.type())));
  }

  // L'entité du signalement est un UUID dérivé de l'identifiant texte du compte.
  @Test
  void lister_notificationsNonEnvoyees_nommeLeCompteParSonIdentifiantDeConnexion() {
    jdbc.update("DELETE FROM users WHERE id = 'compte-signale'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, email, role, username, display_username) VALUES"
            + " ('compte-signale', 'c@test.fr', CAST('user' AS role_utilisateur), 'carlthor',"
            + " 'Carl')",
        Map.of());
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
            + " VALUES ('notifications_non_envoyees', 'utilisateur', gen_random_uuid(), 'haute',"
            + " CAST('{\"userId\": \"compte-signale\"}' AS jsonb))",
        Map.of());

    final ReponseSignalements reponse = service.lister(0);

    assertTrue(
        reponse.signalements().stream()
            .anyMatch(
                e ->
                    "notifications_non_envoyees".equals(e.type())
                        && "carlthor".equals(e.entite().nom())));
  }

  @Test
  void resoudre_signalementActif_leRetireDeLaListe() {
    final long id =
        jdbc.queryForObject(
            "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance)"
                + " VALUES ('collision_wikidata_artiste', 'artiste', CAST(:id AS uuid), 'basse')"
                + " RETURNING id",
            Map.of("id", UUID.randomUUID().toString()),
            Long.class);

    final boolean resolu = service.resoudre(id);

    assertTrue(resolu);
    assertEquals(0, service.lister(0).total());
  }

  // « Marquer comme traité » reconnaît l'absence de couverture : la nuit suivante, le département
  // n'est plus signalé tant qu'il reste sans concert.
  @Test
  void resoudre_departementSansConcert_reconnaitLAbsenceDeCouverture() {
    jdbc.update("DELETE FROM couverture_departements", Map.of());
    jdbc.update(
        "INSERT INTO couverture_departements (dept, nb_concerts, sans_concert_depuis)"
            + " VALUES ('973', 0, CURRENT_DATE)",
        Map.of());
    final long id =
        jdbc.queryForObject(
            "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
                + " VALUES ('departement_sans_concert', 'departement', CAST(:id AS uuid),"
                + " 'moyenne', CAST('{\"departement\": \"973\"}' AS jsonb)) RETURNING id",
            Map.of("id", UUID.randomUUID().toString()),
            Long.class);

    assertTrue(service.resoudre(id));

    assertTrue(
        jdbc.queryForObject(
            "SELECT couverture_connue_le IS NOT NULL FROM couverture_departements"
                + " WHERE dept = '973'",
            Map.of(),
            Boolean.class));
  }

  @Test
  void lister_departementSansConcert_nommeLeDepartement() {
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
            + " VALUES ('departement_sans_concert', 'departement', CAST(:id AS uuid),"
            + " 'moyenne', CAST('{\"departement\": \"976\"}' AS jsonb))",
        Map.of("id", UUID.randomUUID().toString()));

    assertEquals("Département 976", service.lister(0).signalements().getFirst().entite().nom());
  }

  @Test
  void resoudre_signalementInconnu_retourneFalse() {
    assertEquals(false, service.resoudre(999_999L));
  }

  private UUID creerSalle(final String cle) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:cle, :cle, :cle) RETURNING id",
        Map.of("cle", cle + "-" + UUID.randomUUID()),
        UUID.class);
  }

  // La décision « faux positif » est mémorisée sur les sources signalées : le contrôle nocturne
  // ne les re-signalera plus.
  @Test
  void accepterContamination_signalementActif_memoriseLaSourceEtLeveLeSignalement() {
    final UUID salle = creerSalle("agen agora");
    jdbc.update(
        "UPDATE salles SET contamination_signalee = 'centre des congres agen'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:id AS uuid), 'eventim', 'internal:centre des congres agen')",
        Map.of("id", salle.toString()));

    assertTrue(service.accepterContamination(salle));

    final Long acceptees =
        jdbc.queryForObject(
            "SELECT count(*) FROM salle_sources WHERE salle_id = CAST(:id AS uuid)"
                + " AND contamination_acceptee_le IS NOT NULL",
            Map.of("id", salle.toString()),
            Long.class);
    assertEquals(1L, acceptees);
    assertEquals(false, service.accepterContamination(salle), "plus rien à accepter");
  }

  @Test
  void declarerSallesDistinctes_signalementActif_memoriseLaPaireEtLeveLeSignalement() {
    final UUID salleA = creerSalle("theatre montparnasse");
    final UUID salleB = creerSalle("theatre de poche montparnasse");
    jdbc.update(
        "UPDATE salles SET doublon_signale = CAST(:b AS uuid) WHERE id = CAST(:a AS uuid)",
        Map.of("a", salleA.toString(), "b", salleB.toString()));

    assertTrue(service.declarerSallesDistinctes(salleA));

    final Long paires =
        jdbc.queryForObject(
            "SELECT count(*) FROM salles_paires_distinctes"
                + " WHERE (salle_a = CAST(:a AS uuid) AND salle_b = CAST(:b AS uuid))"
                + " OR (salle_a = CAST(:b AS uuid) AND salle_b = CAST(:a AS uuid))",
            Map.of("a", salleA.toString(), "b", salleB.toString()),
            Long.class);
    assertEquals(1L, paires);
    assertEquals(0, service.lister(0).total());
  }

  @Test
  void lister_doublonDeSalle_nommeLAutreSalleSansExposerSonUuid() {
    final UUID salleA = creerSalle("theatre montparnasse");
    final UUID salleB = creerSalle("theatre de poche montparnasse");
    jdbc.update(
        "UPDATE salles SET nom_affiche = 'Théâtre de Poche', ville = 'Paris', dept = '75'"
            + " WHERE id = CAST(:b AS uuid)",
        Map.of("b", salleB.toString()));
    jdbc.update(
        "UPDATE salles SET doublon_signale = CAST(:b AS uuid) WHERE id = CAST(:a AS uuid)",
        Map.of("a", salleA.toString(), "b", salleB.toString()));

    final EntreeSignalement entree = service.lister(0).signalements().getFirst();

    assertEquals(salleB.toString(), entree.autreSalle().id());
    assertEquals("Théâtre de Poche · Paris 75", entree.autreSalle().nom());
    assertNull(entree.details(), "l'UUID de l'autre salle ne doit plus être affiché");
  }

  @Test
  void lister_wikidataPartage_nommeLAutreSalleEtGardeLeResteDesDetails() {
    final UUID porteuse = creerSalle("zenith");
    final UUID signalee = creerSalle("zenith de paris");
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
            + " VALUES ('collision_wikidata_salle', 'salle', CAST(:id AS uuid), 'basse',"
            + " CAST(:details AS jsonb))",
        Map.of(
            "id",
            signalee.toString(),
            "details",
            "{\"wikidataId\": \"Q123\", \"salleExistante\": \"" + porteuse + "\"}"));

    final EntreeSignalement entree = service.lister(0).signalements().getFirst();

    assertEquals(porteuse.toString(), entree.autreSalle().id());
    assertTrue(entree.details().contains("Q123"));
    assertFalse(entree.details().contains(porteuse.toString()));
  }

  @Test
  void lister_signalementSansAutreSalle_laisseLeChampVide() {
    final UUID salle = creerSalle("olympia");
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance)"
            + " VALUES ('wikidata_coordonnees_divergentes', 'salle', CAST(:id AS uuid), 'basse')",
        Map.of("id", salle.toString()));

    assertNull(service.lister(0).signalements().getFirst().autreSalle());
  }

  private long signalerPositionEloignee(final UUID salle) {
    return jdbc.queryForObject(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
            + " VALUES ('position_source_eloignee', 'salle', CAST(:id AS uuid), 'moyenne',"
            + " CAST('{\"latitudeWikidata\": 43.5686, \"longitudeWikidata\": 3.9519}' AS jsonb))"
            + " RETURNING id",
        Map.of("id", salle.toString()),
        Long.class);
  }

  @Test
  void alignerPositionSurWikidata_signalementActif_poseLaPositionDeWikidataEtCloture() {
    final UUID salle = creerSalle("sud de france arena");
    jdbc.update(
        "UPDATE salles SET latitude = 43.6108, longitude = 3.8767, origine_position = 'sources'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    final long signalement = signalerPositionEloignee(salle);

    assertTrue(service.alignerPositionSurWikidata(signalement));

    final Map<String, Object> position =
        jdbc.queryForMap(
            "SELECT latitude, longitude, origine_position FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()));
    assertEquals(43.5686, (Double) position.get("latitude"), 1e-9);
    assertEquals(3.9519, (Double) position.get("longitude"), 1e-9);
    assertEquals("wikidata", position.get("origine_position"));
    assertEquals(false, service.alignerPositionSurWikidata(signalement), "déjà clos");
  }

  @Test
  void alignerPositionSurWikidata_autreType_neModifieRien() {
    final UUID salle = creerSalle("olympia");
    jdbc.update(
        "UPDATE salles SET latitude = 48.87, longitude = 2.33, origine_position = 'sources'"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salle.toString()));
    final long autre =
        jdbc.queryForObject(
            "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
                + " VALUES ('wikidata_coordonnees_divergentes', 'salle', CAST(:id AS uuid),"
                + " 'basse', CAST('{\"latitudeWikidata\": 1.0, \"longitudeWikidata\": 1.0}'"
                + " AS jsonb)) RETURNING id",
            Map.of("id", salle.toString()),
            Long.class);

    assertEquals(false, service.alignerPositionSurWikidata(autre));

    assertEquals(
        48.87,
        jdbc.queryForObject(
            "SELECT latitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            Double.class),
        1e-9);
  }

  private long signalerConcert(final String type) {
    final UUID concert =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, salle_norm, date)"
                + " VALUES ('selah sue', 'chapiteau', CURRENT_DATE + 4) RETURNING id",
            Map.of(),
            UUID.class);
    return jdbc.queryForObject(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance)"
            + " VALUES (:type, 'evenement', CAST(:id AS uuid), 'moyenne') RETURNING id",
        Map.of("type", type, "id", concert.toString()),
        Long.class);
  }

  @Test
  void supprimerConcertSignale_dateDeFestivalEcartee_supprimeEtClot() {
    final long id = signalerConcert("date_festival_ecartee");

    assertTrue(service.supprimerConcertSignale(id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM evenements WHERE artiste_norm = 'selah sue'",
            Map.of(),
            Integer.class));
    assertFalse(service.supprimerConcertSignale(id), "signalement déjà clos");
  }

  // Seuls les signalements dont l'action attendue est la suppression la permettent.
  @Test
  void supprimerConcertSignale_autreType_neSupprimeRien() {
    final long id = signalerConcert("notifications_non_envoyees");

    assertFalse(service.supprimerConcertSignale(id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM evenements WHERE artiste_norm = 'selah sue'",
            Map.of(),
            Integer.class));
  }
}
