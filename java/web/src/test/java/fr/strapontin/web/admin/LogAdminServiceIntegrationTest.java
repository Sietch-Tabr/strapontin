/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.AbstractWebIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test d'intégration de {@link LogAdminService} avec PostgreSQL réel (Testcontainers).
 *
 * <p>{@code LogAdminServiceTest} (mocks) fait transiter le {@code RowMapper} par un {@code
 * jdbc.query} entièrement mocké — il ne l'exerce jamais contre un vrai {@code ResultSet}, ce qui
 * n'aurait pas détecté une conversion {@code timestamptz} incompatible avec {@code rs.getLong}
 * (colonne {@code ts}, bigint → timestamptz).
 *
 * @since 3.0.0
 * @author Kwaite
 */
class LogAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private LogAdminService service;

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
    jdbc.update("DELETE FROM logs_admin", Map.of());
    service = new LogAdminService(jdbc, new ObjectMapper());
  }

  @Test
  void logAdminPuisLireLogs_conserveUnTsCoherentEnEpochMillis() {
    final long avant = Instant.now().toEpochMilli();

    service.logAdmin("info", "test", "message d'intégration");

    final long apres = Instant.now().toEpochMilli();
    final List<LogAdminService.EntreeLogAdmin> entrees = service.lireLogs(10);

    assertEquals(1, entrees.size());
    final LogAdminService.EntreeLogAdmin entree = entrees.get(0);
    assertEquals("message d'intégration", entree.message());
    assertTrue(
        entree.ts() >= avant && entree.ts() <= apres,
        "ts=" + entree.ts() + " attendu entre " + avant + " et " + apres);
  }

  @Test
  void lireErreursDepuis_filtreCorrectementSurLeSeuilTemporel() {
    // ts explicites (écart 1 minute), pas Instant.now() successifs — la résolution de l'horloge
    // système ne garantit pas deux valeurs distinctes entre deux appels rapprochés.
    final long tsAvant = Instant.now().minusSeconds(120).toEpochMilli();
    final long seuil = Instant.now().minusSeconds(60).toEpochMilli();
    final long tsApres = Instant.now().toEpochMilli();
    inserer(tsAvant, "erreur avant seuil");
    inserer(tsApres, "erreur après seuil");

    final List<LogAdminService.EntreeLogAdmin> entrees = service.lireErreursDepuis(seuil);

    assertEquals(1, entrees.size());
    assertEquals("erreur après seuil", entrees.get(0).message());
  }

  private void inserer(final long tsEpochMs, final String message) {
    jdbc.update(
        "INSERT INTO logs_admin (ts, niveau, source, message) VALUES (:ts, 'error', 'test',"
            + " :message)",
        Map.of("ts", Timestamp.from(Instant.ofEpochMilli(tsEpochMs)), "message", message));
  }
}
