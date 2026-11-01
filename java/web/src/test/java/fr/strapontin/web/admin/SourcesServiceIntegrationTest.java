/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.salle.SalleService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Onglet Sources sur base réelle : chaque source qui a produit des observations y
 * figure, sites de salles compris.
 */
class SourcesServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private SourcesService service;

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
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    service =
        new SourcesService(
            jdbc, new ObjectMapper(), mock(EventimSource.class), mock(SalleService.class));
  }

  private void observation(final String source, final UUID salle, final String identifiant) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salle AS uuid), CAST(:source AS text), :identifiant)"
            + " ON CONFLICT DO NOTHING",
        Map.of("salle", salle.toString(), "source", source, "identifiant", identifiant));
    final UUID evenement =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id)"
                + " VALUES (:norm, CURRENT_DATE + 30, 's', CAST(:salle AS uuid)) RETURNING id",
            Map.of("norm", "artiste " + UUID.randomUUID(), "salle", salle.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST(:source AS source_concert), :url, 'a', 's')",
        Map.of("id", evenement.toString(), "source", source, "url", "https://x/" + evenement));
  }

  private UUID salle(final String cle) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:cle, :cle, :cle) RETURNING id",
        Map.of("cle", cle),
        UUID.class);
  }

  @SuppressWarnings("unchecked")
  private List<StatutSource> sources() {
    return (List<StatutSource>) service.lireSources().get("sources");
  }

  @Test
  void lireSources_sitesDeSalles_apparaissentAvecLeurPropreDernierPassage() {
    observation("site_salle", salle("triton"), "triton");
    observation("site_salle", salle("zion"), "allmol:zion park");
    observation("site_salle", salle("lacaz"), "allmol:lacaz art");

    final Map<String, StatutSource> parId =
        sources().stream().collect(Collectors.toMap(StatutSource::id, s -> s));

    assertTrue(parId.containsKey("site_salle:triton"));
    assertEquals("Allmol Tickets", parId.get("site_salle:allmol").nom());
    assertEquals(2, parId.get("site_salle:allmol").nbSallesConfigurees());
    assertNotNull(parId.get("site_salle:allmol").dernierFetch());
    assertEquals("ok", parId.get("site_salle:triton").etat());
  }

  // Une source ajoutée (une classe, un bean) ne doit pas rester absente de l'onglet.
  @Test
  void lireSources_chaqueSourceAyantDesObservations_figureDansLOnglet() {
    observation("ticketmaster", salle("a"), "tm1");
    observation("eventim", salle("b"), "internal:b");
    observation("site_salle", salle("c"), "emb");

    final Set<String> ids = sources().stream().map(StatutSource::id).collect(Collectors.toSet());
    final Set<String> observees =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT DISTINCT source::text FROM observations", Map.of(), String.class));

    for (final String source : observees) {
      assertTrue(
          ids.stream().anyMatch(id -> id.equals(source) || id.startsWith(source + ":")),
          "source absente de l'onglet : " + source + " parmi " + ids);
    }
  }
}
