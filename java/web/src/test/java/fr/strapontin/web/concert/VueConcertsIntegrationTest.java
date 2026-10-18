/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.concert;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

/**
 * Choix de l'observation affichée par la vue {@code concerts} sur PostgreSQL réel : priorité de
 * source, puis observation la plus récemment confirmée à priorité égale.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class VueConcertsIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private UUID evenementId;

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
    jdbc.update("DELETE FROM evenements", Map.of());
    evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm)"
                + " VALUES ('zebrahead', :date, 'alhambra') RETURNING id",
            new MapSqlParameterSource("date", LocalDate.now().plusMonths(1)),
            UUID.class);
  }

  private void observer(
      final String source, final String url, final String salle, final int joursDepuisMaj) {
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
            + " first_seen, updated_at)"
            + " VALUES (CAST(:id AS uuid), CAST(:source AS source_concert), :url, 'Zebrahead',"
            + " :salle, now() - interval '30 days',"
            + " now() - make_interval(days => :jours))",
        new MapSqlParameterSource()
            .addValue("id", evenementId.toString())
            .addValue("source", source)
            .addValue("url", url)
            .addValue("salle", salle)
            .addValue("jours", joursDepuisMaj));
  }

  private String salleAffichee() {
    return jdbc.queryForObject(
        "SELECT salle FROM concerts WHERE id = CAST(:id AS uuid)",
        Map.of("id", evenementId.toString()),
        String.class);
  }

  @Test
  void concerts_deuxObservationsDeMemeSource_afficheLaPlusRecemmentConfirmee() {
    observer("ticketmaster", "https://tm/642731", "La Maroquinerie", 7);
    observer("ticketmaster", "https://tm/668864", "Alhambra", 1);

    assertEquals("Alhambra", salleAffichee());
  }

  @Test
  void concerts_ordreDInsertionInverse_afficheToujoursLaPlusRecemmentConfirmee() {
    observer("ticketmaster", "https://tm/668864", "Alhambra", 1);
    observer("ticketmaster", "https://tm/642731", "La Maroquinerie", 7);

    assertEquals("Alhambra", salleAffichee());
  }

  @Test
  void concerts_sourcesDifferentes_laPrioriteDeSourcePrimeSurLaFraicheur() {
    observer("eventim", "https://eventim/1", "Alhambra Eventim", 0);
    observer("ticketmaster", "https://tm/1", "Alhambra", 9);

    assertEquals("Alhambra", salleAffichee());
  }
}
