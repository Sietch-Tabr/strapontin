/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.PositionLieu;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Rattrapage nocturne de la position des lieux Ticketmaster ({@link
 * HealthCheckSallesTM#rattraperPositions}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class HealthCheckSallesTMIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private TicketmasterSource tm;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private HealthCheckSallesTM healthCheck;

  private UUID creerSalleTm(final String venueId, final Double latitude) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept)"
            + " VALUES (CAST(:id AS uuid), :id, 'Salle', 'salle', '75')",
        Map.of("id", id.toString()));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), 'ticketmaster', :venue, :lat, :lat)",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("venue", venueId)
            .addValue("lat", latitude, Types.DOUBLE));
    return id;
  }

  @Test
  void rattraperPositions_lieuSansCoordonnees_enregistrePositionSurMappingEtSalle() {
    final UUID salle = creerSalleTm("Z698xZG2Z17", null);
    when(tm.lirePositionVenue("Z698xZG2Z17"))
        .thenReturn(new PositionLieu(48.83861, 2.37861, "75012"));

    assertEquals(1, healthCheck.rattraperPositions());

    assertEquals(
        "75012",
        jdbc.queryForObject(
            "SELECT code_postal FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            String.class));
    assertEquals(
        48.83861,
        jdbc.queryForObject(
            "SELECT latitude FROM salle_sources WHERE identifiant_externe = 'Z698xZG2Z17'",
            Map.of(),
            Double.class),
        1e-9);
  }

  @Test
  void rattraperPositions_lieuDejaGeolocalise_nInterrogePasTicketmaster() {
    creerSalleTm("Z698xZG2Z18", 48.8);

    assertEquals(0, healthCheck.rattraperPositions());
    verify(tm, never()).lirePositionVenue(anyString());
  }
}
