/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Correction de données de {@code V75__reference_inactivite_comptes.sql}, rejouée (idempotente)
 * sur des comptes représentatifs après migration complète.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class MigrationReferenceInactiviteIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

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

  @Test
  void migration_compteActiveSansDate_prendUneReferenceEtPerdSaPurgeProgrammee()
      throws IOException {
    creerCompte("active", false, dansJours(10), null);
    creerCompte("invite", true, null, null);
    creerCompte("connu", false, dansJours(10), dansJours(-240));

    rejouerMigration();

    assertNotNull(lire("active", "derniere_connexion_le"));
    assertNull(lire("active", "purge_le"));
    assertNull(lire("invite", "derniere_connexion_le"));
    assertNotNull(lire("connu", "purge_le"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE id = 'connu'"
                + " AND derniere_connexion_le < now() - interval '7 months'",
            Map.of(),
            Integer.class));
  }

  private static Timestamp dansJours(final int jours) {
    return Timestamp.from(Instant.now().plus(Duration.ofDays(jours)));
  }

  private static void creerCompte(
      final String id, final boolean invite, final Timestamp purgeLe, final Timestamp derniere) {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials, purge_le,"
            + " derniere_connexion_le) VALUES (:id, :id, :email, :invite, :purgeLe, :derniere)",
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("email", id + "@test.fr")
            .addValue("invite", invite)
            .addValue("purgeLe", purgeLe, Types.TIMESTAMP)
            .addValue("derniere", derniere, Types.TIMESTAMP));
  }

  private static Object lire(final String id, final String colonne) {
    return jdbc.queryForList("SELECT * FROM users WHERE id = :id", Map.of("id", id))
        .get(0)
        .get(colonne);
  }

  private static void rejouerMigration() throws IOException {
    final String sql =
        new ClassPathResource("db/migration/V75__reference_inactivite_comptes.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    jdbc.getJdbcOperations().execute(sql);
  }
}
