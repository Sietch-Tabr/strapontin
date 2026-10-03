/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.time.LocalDate;
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
}
