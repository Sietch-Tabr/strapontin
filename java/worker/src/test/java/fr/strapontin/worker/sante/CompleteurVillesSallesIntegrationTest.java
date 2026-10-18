/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.LogsAdminWorker;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Villes des salles et des événements sur base réelle : orthographe unique, ville déduite des
 * concerts puis des lieux sources, département déduit du code postal.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class CompleteurVillesSallesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private CompleteurVillesSalles completeur;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
    completeur = new CompleteurVillesSalles(jdbc, logsAdminWorker);
  }

  private UUID salle(final String nom, final String ville, final String dept) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, ville) VALUES (:cle, :nom, :cle,"
            + " :dept, :ville) RETURNING id",
        new MapSqlParameterSource()
            .addValue("cle", nom.toLowerCase())
            .addValue("nom", nom)
            .addValue("dept", dept)
            .addValue("ville", ville),
        UUID.class);
  }

  private void evenement(final UUID salleId, final String artiste, final String ville) {
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id, ville)"
            + " VALUES (:artiste, :date, 'x', CAST(:salle AS uuid), :ville)",
        new MapSqlParameterSource()
            .addValue("artiste", artiste)
            .addValue("date", LocalDate.now().plusDays(10))
            .addValue("salle", salleId.toString())
            .addValue("ville", ville));
  }

  private String villeSalle(final UUID salleId) {
    return jdbc.queryForObject(
        "SELECT ville FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()),
        String.class);
  }

  @Test
  void completer_villeDeLaSalleAbsente_reprendLaVilleDeSesConcerts() {
    final UUID salleId = salle("Salle A", null, "93");
    evenement(salleId, "a", "Tremblay en France");
    evenement(salleId, "b", "Tremblay en France");
    evenement(salleId, "c", "Autre Ville");

    completeur.completer();

    assertEquals("Tremblay en France", villeSalle(salleId));
  }

  @Test
  void completer_orthographesDifferentesDUneVille_sontUnifiees() {
    final UUID salleId = salle("Salle B", "Nantes, France", "44");
    evenement(salleId, "a", "Tremblay En France");
    evenement(salleId, "b", "Tremblay en France");

    completeur.completer();

    assertEquals("Nantes", villeSalle(salleId));
    assertEquals(
        1L,
        jdbc.queryForObject("SELECT count(DISTINCT ville) FROM evenements", Map.of(), Long.class));
  }

  @Test
  void completer_sansConcertAvecLieuSource_reprendLaVilleDuLieu() {
    final UUID salleId = salle("Salle C", null, "77");
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, ville_source)"
            + " VALUES (CAST(:id AS uuid), 'eventim', 'eventim:internal:c', 'Savigny le Temple"
            + " Cedex')",
        Map.of("id", salleId.toString()));

    completeur.completer();

    assertEquals("Savigny le Temple", villeSalle(salleId));
  }

  @Test
  void completer_departementAbsentCodePostalConnu_deduitLeDepartement() {
    final UUID salleId = salle("Opéra-Comique", null, null);
    jdbc.update(
        "UPDATE salles SET code_postal = '75002' WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));

    completeur.completer();

    assertEquals(
        "75",
        jdbc.queryForObject(
            "SELECT dept FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            String.class));
  }

  @Test
  void completer_villeIndeterminable_signaleLaSalleDansLeJournalAdmin() {
    final UUID salleId = salle("Salle D", null, "13");

    completeur.completer();

    assertNull(villeSalle(salleId));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE source = 'villes-salles' AND niveau = 'warn'",
            Map.of(),
            Long.class));
  }
}
