/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Départements choisis sans concert connu ({@link UserConfigService}) sur
 * PostgreSQL réel.
 *
 * @since 3.0.4
 * @author Kwaite
 */
class UserConfigServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private UserConfigService service;

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
    jdbc.update("DELETE FROM users WHERE id = 'u-dept'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username)"
            + " VALUES ('u-dept', 'u-dept@test.fr', CAST('user' AS role_utilisateur), 'udept')",
        Map.of());
    jdbc.update(
        "INSERT INTO user_config (user_id, departements)"
            + " VALUES ('u-dept', ARRAY['974', '75', '13'])",
        Map.of());
    service = new UserConfigService(jdbc, mock(ConfigService.class), mock(LogAdminService.class));
  }

  private void evenement(final String dept, final String decalage) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, salle_norm, date, dept)"
            + " VALUES ('a', 's', CURRENT_DATE + CAST(:decalage AS integer), :dept)",
        Map.of("dept", dept, "decalage", decalage));
  }

  @Test
  void departementsSansConcert_seulsLesConcertsAVenirComptent() {
    evenement("75", "10");
    evenement("13", "-3");

    assertEquals(List.of("13", "974"), service.departementsSansConcert("u-dept"));
  }
}
