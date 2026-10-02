/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Dispersion géographique des lieux sources ({@link ControleDispersionGeo}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ControleDispersionGeoIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SignalementMatchingService signalements;

  private ControleDispersionGeo controle;

  @BeforeEach
  void setUp() {
    controle = new ControleDispersionGeo(jdbc, signalements);
  }

  private UUID creerSalle(final String nom) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm) VALUES (CAST(:id AS uuid), :id, :nom,"
            + " :nom)",
        Map.of("id", id.toString(), "nom", nom));
    return id;
  }

  private void lieu(
      final UUID salle, final String identifiant, final double lat, final double lon) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), 'eventim', :identifiant, :lat, :lon)",
        new MapSqlParameterSource()
            .addValue("id", salle.toString())
            .addValue("identifiant", identifiant)
            .addValue("lat", lat)
            .addValue("lon", lon));
  }

  private List<String> typesSignales() {
    return jdbc.queryForList(
        "SELECT type FROM signalements_matching ORDER BY type", Map.of(), String.class);
  }

  // La Comédie Bastille (11e) et la Comédie de Paris (9e), ~3 km, sous une même salle.
  @Test
  void verifier_salleAuxLieuxSourcesEloignes_signaleUneDispersion() {
    final UUID salle = creerSalle("la comedie bastille paris");
    lieu(salle, "internal:la comedie bastille paris@75011", 48.8559, 2.3713);
    lieu(salle, "internal:comedie de paris paris 09@75009", 48.8820, 2.3361);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("dispersion_sources_salle"), typesSignales());
  }

  // Parc des Expos Hermione / Parc des Expositions Brézillet : même lieu, noms sans rapport.
  @Test
  void verifier_deuxSallesAuMemeLieu_signaleUnDoublonSansDependreDesNoms() {
    final UUID hermione = creerSalle("parc des expos hermione");
    final UUID brezillet = creerSalle("parc des expositions brezillet");
    lieu(hermione, "internal:hermione@22000", 48.5120, -2.7480);
    lieu(brezillet, "internal:brezillet@22000", 48.5121, -2.7481);

    assertEquals(1, controle.verifier());
    assertEquals(List.of("meme_lieu_salles"), typesSignales());
  }

  @Test
  void verifier_complexeEtSousSalleAuMemeLieu_neSignalePas() {
    final UUID complexe = creerSalle("la seine musicale");
    final UUID sousSalle = creerSalle("la seine musicale grande seine");
    jdbc.update(
        "UPDATE salles SET salle_parent_id = CAST(:p AS uuid) WHERE id = CAST(:id AS uuid)",
        Map.of("p", complexe.toString(), "id", sousSalle.toString()));
    lieu(complexe, "internal:la seine musicale@92100", 48.8296, 2.2404);
    lieu(sousSalle, "internal:grande seine@92100", 48.8296, 2.2404);

    assertEquals(0, controle.verifier());
  }
}
