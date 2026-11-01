/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Test d'intégration de {@link ConcertsAdminService} avec PostgreSQL réel (Testcontainers).
 *
 * <p>{@code ConcertsAdminServiceTest} (mocks) fait transiter le {@code RowMapper} par un {@code
 * jdbc.query} entièrement mocké — il ne l'exerce jamais contre un vrai {@code ResultSet}, ce qui a
 * laissé passer une conversion {@code timestamptz} → {@code Instant} non supportée par le driver PostgreSQL
 * ({@code rs.getObject(col, Instant.class)}), invisible tant qu'aucune ligne réelle n'avait {@code
 * suppression_suggeree_le} renseigné.
 *
 * @since 2.8.4
 * @author Kwaite
 */
class ConcertsAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private ConcertsAdminService service;

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
    // evenements, pas concerts (vue depuis #1192) — cascade vers observations.
    jdbc.update("DELETE FROM evenements", Map.of());
    service = new ConcertsAdminService(jdbc, new LogAdminService(jdbc, new ObjectMapper()));
  }

  private void creerConcertFlague(final String artiste) {
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, absences_consecutives,"
                + " suppression_suggeree_le)"
                + " VALUES (:artisteNorm, :date, 'salle test', 50, now()) RETURNING id",
            new MapSqlParameterSource()
                .addValue("artisteNorm", artiste.toLowerCase())
                .addValue("date", LocalDate.now()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), :artiste,"
            + " 'Salle Test')",
        Map.of("id", evenementId.toString(), "artiste", artiste));
  }

  @Test
  void lister_concertFlague_mappeSuppressionSuggereeLeSansErreur() {
    creerConcertFlague("Vincent Dedienne");

    final ReponseConcertsAConfirmer reponse = service.lister(0);

    assertEquals(1, reponse.concerts().size());
    assertEquals("Vincent Dedienne", reponse.concerts().get(0).artiste());
    assertNotNull(reponse.concerts().get(0).suppressionSuggereeLe());
    assertEquals(1L, reponse.total());
  }

  private UUID creerFiche(
      final String artiste,
      final int absences,
      final boolean flague,
      final String source,
      final String url,
      final int joursDObservation) {
    final UUID salleId = salleDeTest();
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id,"
                + " absences_consecutives, suppression_suggeree_le)"
                + " VALUES (:artiste, :date, 'salle test', CAST(:salleId AS uuid), :absences,"
                + " CASE WHEN :flague THEN now() END) RETURNING id",
            new MapSqlParameterSource()
                .addValue("artiste", artiste)
                .addValue("date", LocalDate.now().plusDays(10 + absences))
                .addValue("salleId", salleId.toString())
                .addValue("absences", absences)
                .addValue("flague", flague),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
            + " updated_at) VALUES (CAST(:id AS uuid), CAST(:source AS source_concert), :url,"
            + " :artiste, 'Salle Test', now() - make_interval(days => :jours))",
        Map.of(
            "id",
            evenementId.toString(),
            "source",
            source,
            "url",
            url,
            "artiste",
            artiste,
            "jours",
            joursDObservation));
    return evenementId;
  }

  private UUID salleDeTest() {
    final List<UUID> existantes =
        jdbc.queryForList(
            "SELECT id FROM salles WHERE salle_cle = 'salle test'", Map.of(), UUID.class);
    if (!existantes.isEmpty()) {
      return existantes.get(0);
    }
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES ('salle test', 'Salle Test',"
            + " 'salle test') RETURNING id",
        Map.of(),
        UUID.class);
  }

  private String situationDe(final String artiste) {
    return service.lister(0).concerts().stream()
        .filter(c -> c.artiste().equals(artiste))
        .findFirst()
        .orElseThrow()
        .situation();
  }

  @Test
  void lister_memeProduitEventimVivantAilleursDansLaSalle_situationJumeau() {
    creerFiche(
        "nieve ella", 50, true, "eventim", "https://www.eventim.fr/event/nieve-ella-1111/", 30);
    creerFiche(
        "nieve ella", 0, false, "eventim", "https://www.eventim.fr/event/nieve-ella-1111/", 0);

    assertEquals("jumeau", situationDe("nieve ella"));
  }

  @Test
  void lister_autreBilletterieLaVoitEncore_situationAnnonceeAilleurs() {
    creerFiche("les 1001 nuits du jazz", 50, true, "site_salle", "https://salle/jazz", 1);

    assertEquals("annoncee_ailleurs", situationDe("les 1001 nuits du jazz"));
  }

  @Test
  void lister_aucuneSourceNeLaVoit_situationIntrouvable() {
    creerFiche(
        "dominique blanc", 50, true, "eventim", "https://www.eventim.fr/event/blanc-2222/", 40);

    assertEquals("introuvable", situationDe("dominique blanc"));
  }

  @Test
  void annulerSuppression_concertFlague_leDeclareMaintenu() {
    creerConcertFlague("Rabah Asma");
    final UUID id = jdbc.queryForObject("SELECT id FROM evenements", Map.of(), UUID.class);

    assertTrue(service.annulerSuppression(id));

    final Map<String, Object> ligne =
        jdbc.queryForMap(
            "SELECT maintenu_le IS NOT NULL AS maintenu, suppression_suggeree_le,"
                + " absences_consecutives FROM evenements",
            Map.of());
    assertEquals(true, ligne.get("maintenu"));
    assertNull(ligne.get("suppression_suggeree_le"));
    assertEquals(0, ligne.get("absences_consecutives"));
  }
}
