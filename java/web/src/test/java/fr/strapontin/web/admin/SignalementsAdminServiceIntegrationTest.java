/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
