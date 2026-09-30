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
  void lireErreursDepuis_horodatageMicroseconde_neRenvoiePasUneErreurDejaNotifiee() {
    inserer(Instant.parse("2026-09-29T07:43:31.247604Z"), "erreur à la microseconde");
    final List<LogAdminService.EntreeLogAdmin> premierLot = service.lireErreursDepuis(0L);

    final List<LogAdminService.EntreeLogAdmin> secondLot =
        service.lireErreursDepuis(premierLot.get(0).id());

    assertEquals(1, premierLot.size());
    assertEquals(List.of(), secondLot);
  }

  @Test
  void lireErreursDepuis_deuxErreursDansLaMemeMilliseconde_renvoieLesDeux() {
    inserer(Instant.parse("2026-09-29T07:43:31.247100Z"), "première");
    inserer(Instant.parse("2026-09-29T07:43:31.247900Z"), "seconde");

    final List<LogAdminService.EntreeLogAdmin> entrees = service.lireErreursDepuis(0L);

    assertEquals(List.of("première", "seconde"), entrees.stream().map(LogAdminService.EntreeLogAdmin::message).toList());
  }

  @Test
  void idMaxJusquA_ancienCurseurTronqueALaMilliseconde_couvreLErreurDejaNotifiee() {
    inserer(Instant.parse("2026-09-29T07:43:31.247604Z"), "déjà notifiée");
    inserer(Instant.parse("2026-09-29T07:43:31.248100Z"), "nouvelle");
    final long ancienCurseur = Instant.parse("2026-09-29T07:43:31.247Z").toEpochMilli();

    final long id = service.idMaxJusquA(ancienCurseur);

    assertEquals(List.of("nouvelle"), service.lireErreursDepuis(id).stream()
            .map(LogAdminService.EntreeLogAdmin::message)
            .toList());
  }

  private void inserer(final Instant ts, final String message) {
    jdbc.update(
        "INSERT INTO logs_admin (ts, niveau, source, message) VALUES (:ts, 'error', 'test',"
            + " :message)",
        Map.of("ts", Timestamp.from(ts), "message", message));
  }
}
