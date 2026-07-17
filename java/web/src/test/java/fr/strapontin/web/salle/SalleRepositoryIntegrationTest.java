/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Test d'intégration de {@link SalleRepository} avec PostgreSQL réel (Testcontainers).
///
/// <p>Vérifie spécifiquement {@link SalleRepository#lireIdentifiantsSources} (#966) : le
/// préfixe Ticketmaster construit à partir de {@code salle_sources.source} doit être abrégé
/// ({@code "tm"}) pour correspondre à ce qu'attend
/// {@code TicketmasterSource.accepteIdentifiantSalle} — sans quoi le fetch déclenché à l'ajout
/// d'une salle TM ne fait jamais rien.
///
/// @since 2.7.1
/// @author Kwaite
class SalleRepositoryIntegrationTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  private static NamedParameterJdbcTemplate jdbc;

  private SalleRepository repository;

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
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    repository = new SalleRepository(jdbc);
  }

  private UUID creerSalle(String salleCle) {
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:cle, :cle, :cle)",
        Map.of("cle", salleCle));
    return jdbc.queryForObject(
        "SELECT id FROM salles WHERE salle_cle = :cle",
        Map.of("cle", salleCle),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  @Test
  void lireIdentifiantsSources_sourceTicketmaster_abregeLePrefixeEnTm() {
    UUID salleId = creerSalle("zenith paris");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'ticketmaster', 'rZASnyZ1eE')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("tm:rZASnyZ1eE", identifiants);
  }

  @Test
  void lireIdentifiantsSources_sourceDigitick_conservePrefixeInchange() {
    UUID salleId = creerSalle("lempreinte");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:uuid AS uuid), 'digitick', 'lempreinte')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertEquals("digitick:lempreinte", identifiants);
  }

  @Test
  void lireIdentifiantsSources_plusieursSources_melangeAbregeEtInchange() {
    UUID salleId = creerSalle("bataclan");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe) VALUES"
            + " (CAST(:uuid AS uuid), 'ticketmaster', 'rZ6SnyZe6d'),"
            + " (CAST(:uuid AS uuid), 'eventim', 'internal:le bataclan')",
        Map.of("uuid", salleId.toString()));

    String identifiants = repository.lireIdentifiantsSources(salleId);

    assertTrue(identifiants.contains("tm:rZ6SnyZe6d"));
    assertTrue(identifiants.contains("eventim:internal:le bataclan"));
    assertTrue(
        !identifiants.contains("ticketmaster:"),
        "le préfixe complet ticketmaster: ne doit plus apparaître");
  }

  @Test
  void lireIdentifiantsSources_aucuneSource_retourneNull() {
    UUID salleId = creerSalle("salle sans source");

    assertNull(repository.lireIdentifiantsSources(salleId));
  }
}
