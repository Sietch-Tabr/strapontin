/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.AbstractWebIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/// Test d'intégration de {@link ConcertsAdminService} avec PostgreSQL réel (Testcontainers).
///
/// <p>{@code ConcertsAdminServiceTest} (mocks) fait transiter le `RowMapper` par un {@code
/// jdbc.query} entièrement mocké — il ne l'exerce jamais contre un vrai {@code ResultSet}, ce qui a
/// laissé passer une conversion `timestamptz` → `Instant` non supportée par le driver PostgreSQL
/// (`rs.getObject(col, Instant.class)`), invisible tant qu'aucune ligne réelle n'avait {@code
/// suppression_suggeree_le} renseigné.
///
/// @since 2.8.4
/// @author Kwaite
class ConcertsAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private ConcertsAdminService service;

  @BeforeAll
  static void migrerBase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM concerts", Map.of());
    service = new ConcertsAdminService(jdbc, new LogAdminService(jdbc, new ObjectMapper()));
  }

  private void creerConcertFlague(String artiste) {
    jdbc.update(
        "INSERT INTO concerts (artiste, artiste_norm, date, salle, salle_norm, source,"
            + " absences_consecutives, suppression_suggeree_le)"
            + " VALUES (:artiste, :artisteNorm, :date, 'Salle Test', 'salle test',"
            + " CAST('ticketmaster' AS source_concert), 50, now())",
        Map.of("artiste", artiste, "artisteNorm", artiste.toLowerCase(), "date", LocalDate.now()));
  }

  @Test
  void lister_concertFlague_mappeSuppressionSuggereeLeSansErreur() {
    creerConcertFlague("Vincent Dedienne");

    ReponseConcertsAConfirmer reponse = service.lister(0);

    assertEquals(1, reponse.concerts().size());
    assertEquals("Vincent Dedienne", reponse.concerts().get(0).artiste());
    assertNotNull(reponse.concerts().get(0).suppressionSuggereeLe());
    assertEquals(1L, reponse.total());
  }
}
