/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Départements sans concert à venir vus par l'admin, sur base réelle.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class CouvertureDepartementsAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private CouvertureDepartementsAdminService service;

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
    jdbc.update("DELETE FROM couverture_departements", Map.of());
    jdbc.update("DELETE FROM signalements_matching", Map.of());
    service =
        new CouvertureDepartementsAdminService(jdbc, new LogAdminService(jdbc, new ObjectMapper()));
  }

  private void relever(final String dept, final int nombre, final boolean reconnue) {
    jdbc.update(
        "INSERT INTO couverture_departements (dept, nb_concerts, sans_concert_depuis,"
            + " couverture_connue_le) VALUES (:dept, :nb, CASE WHEN :nb = 0 THEN CURRENT_DATE END,"
            + " CASE WHEN :reconnue THEN now() END)",
        Map.of("dept", dept, "nb", nombre, "reconnue", reconnue));
  }

  @Test
  void lister_departementsAZero_lesAExaminerDAbord() {
    relever("975", 0, true);
    relever("971", 0, false);
    relever("75", 120, false);

    final var liste = service.lister();

    assertEquals(2, liste.size());
    assertEquals("971", liste.get(0).dept());
    assertFalse(liste.get(0).absenceReconnue());
    assertEquals("975", liste.get(1).dept());
    assertTrue(liste.get(1).absenceReconnue());
  }

  @Test
  void reconnaitre_departementSansConcert_ferme_leSignalementActif() {
    relever("976", 0, false);
    jdbc.update(
        "INSERT INTO signalements_matching (type, entite_type, entite_id, confiance, details)"
            + " VALUES ('departement_sans_concert', 'departement', gen_random_uuid(), 'moyenne',"
            + " CAST('{\"departement\": \"976\"}' AS jsonb))",
        Map.of());

    assertTrue(service.reconnaitre("976"));

    assertTrue(service.lister().getFirst().absenceReconnue());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM signalements_matching WHERE resolu_le IS NULL",
            Map.of(),
            Long.class));
  }

  @Test
  void reconnaitre_departementAvecConcerts_estRefuse() {
    relever("75", 120, false);

    assertFalse(service.reconnaitre("75"));
  }

  @Test
  void rouvrir_absenceReconnue_lalevePourLeProchainReleve() {
    relever("973", 0, true);

    assertTrue(service.rouvrir("973"));

    assertFalse(service.lister().getFirst().absenceReconnue());
    assertFalse(service.rouvrir("973"), "plus rien à rouvrir");
  }
}
