/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.strapontin.web.AbstractWebIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Recherche d'artistes sur base réelle : les variantes d'espacement (« Krash Karma » et
 * « Krashkarma ») se retrouvent dans les deux sens, ce qu'un dépôt simulé ne peut pas montrer.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class ArtisteRepositoryIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private ArtisteRepository repository;

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
    jdbc.update("DELETE FROM artistes", Map.of());
    repository = new ArtisteRepository(jdbc);
  }

  private void creerArtiste(final String nom, final String nomNorm) {
    jdbc.update(
        "INSERT INTO artistes (nom, nom_norm) VALUES (:nom, :nomNorm)",
        Map.of("nom", nom, "nomNorm", nomNorm));
  }

  @Test
  void chercherCandidatsParTokens_nomSansEspaceCherchePuisEspace_retrouveLArtiste() {
    creerArtiste("Krash Karma", "krash karma");

    final List<Map<String, Object>> candidats =
        repository.chercherCandidatsParTokens(Set.of("krashkarma"), "krashkarma");

    assertEquals(1, candidats.size());
    assertEquals("krash karma", candidats.getFirst().get("nom_norm"));
  }

  @Test
  void chercherCandidatsParTokens_nomAvecEspaceCherchePuisSansEspace_retrouveLArtiste() {
    creerArtiste("Krashkarma", "krashkarma");

    final List<Map<String, Object>> candidats =
        repository.chercherCandidatsParTokens(Set.of("krash", "karma"), "krashkarma");

    assertEquals(1, candidats.size());
  }

  @Test
  void chercherCandidatsParTokens_aucunTokenMaisNomCompact_retrouveLArtiste() {
    creerArtiste("Ed Sheeran", "ed sheeran");

    assertEquals(1, repository.chercherCandidatsParTokens(Set.of(), "edsheeran").size());
    assertTrue(repository.chercherCandidatsParTokens(Set.of(), "").isEmpty());
  }

  @Test
  void chercherCandidatsParTokens_artisteSansRapport_nEstPasRetourne() {
    creerArtiste("Radiohead", "radiohead");

    assertTrue(repository.chercherCandidatsParTokens(Set.of("krashkarma"), "krashkarma").isEmpty());
  }

  private void creerConcert(final String artisteBrut, final String artisteNorm, final int jours) {
    final java.util.UUID evenement =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, salle_norm, date)"
                + " VALUES (:norm, 'salle', CURRENT_DATE + :jours) RETURNING id",
            Map.of("norm", artisteNorm, "jours", jours),
            java.util.UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (:id, 'eventim', :url, :artiste, 'Salle')",
        Map.of(
            "id",
            evenement,
            "url",
            "https://exemple.fr/" + artisteNorm.hashCode(),
            "artiste",
            artisteBrut));
  }

  @Test
  void rechercherEnBase_espacesIgnoresDesDeuxCotes() {
    creerConcert("Krashkarma", "krashkarma", 5);
    creerConcert("Krash Karma", "krash karma", 6);

    final List<String> tousLesDeux = repository.rechercherEnBase("%krashkarma%");

    assertEquals(Set.of("Krash Karma", "Krashkarma"), Set.copyOf(tousLesDeux));
  }

  @Test
  void rechercherEnBase_artisteSansRapport_nEstPasRetourne() {
    creerConcert("Radiohead", "radiohead", 5);

    assertTrue(repository.rechercherEnBase("%krashkarma%").isEmpty());
  }
}
