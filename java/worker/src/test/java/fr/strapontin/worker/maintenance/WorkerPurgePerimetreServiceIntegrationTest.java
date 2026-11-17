/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Purge des fiches hors du périmètre de tout utilisateur
 * ({@link WorkerPurgePerimetreService}).
 */
class WorkerPurgePerimetreServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private TicketmasterSource ticketmasterSourceIgnore;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private WorkerPurgePerimetreService service;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM user_config", Map.of());
    jdbc.update("DELETE FROM users", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM artistes", Map.of());
  }

  private UUID fiche(final String dept, final UUID salle, final UUID artiste, final int jours) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, heure, salle_norm, dept, salle_id,"
                + " artiste_id) VALUES (:norm, CURRENT_DATE + 30, '20:00', 'salle', :dept,"
                + " CAST(:salle AS uuid), CAST(:artiste AS uuid)) RETURNING id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("norm", "fiche " + UUID.randomUUID())
                .addValue("dept", dept)
                .addValue("salle", salle == null ? null : salle.toString())
                .addValue("artiste", artiste == null ? null : artiste.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut,"
            + " updated_at) VALUES (CAST(:id AS uuid), 'ticketmaster', :url, 'a', 's',"
            + " now() - make_interval(days => :jours))",
        Map.of("id", id.toString(), "url", "https://tm/" + id, "jours", jours));
    return id;
  }

  private UUID salle() {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:cle, 's', :cle) RETURNING id",
        Map.of("cle", "salle " + UUID.randomUUID()),
        UUID.class);
  }

  private UUID artiste() {
    return jdbc.queryForObject(
        "INSERT INTO artistes (nom, nom_norm) VALUES ('a', :norm) RETURNING id",
        Map.of("norm", "artiste " + UUID.randomUUID()),
        UUID.class);
  }

  private void utilisateur(final String id, final String dept) {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials)"
            + " VALUES (:id, :id, :id || '@test.fr', false)",
        Map.of("id", id));
    jdbc.update(
        "INSERT INTO user_config (user_id, departements) VALUES (:id, ARRAY[:dept]::text[])",
        Map.of("id", id, "dept", dept));
  }

  private List<UUID> restantes() {
    return jdbc.queryForList("SELECT id FROM evenements", Map.of(), UUID.class);
  }

  @Test
  void executerPurgerConcertsHorsPerimetre_departementNonSuiviSansObservationRecente_supprime() {
    utilisateur("u1", "75");
    final UUID horsPerimetre = fiche("69", null, null, 20);

    service.executerPurgerConcertsHorsPerimetre();

    assertEquals(List.of(), restantes());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE source = 'purge-concerts'",
            Map.of(),
            Long.class),
        "la suppression de " + horsPerimetre + " est tracée");
  }

  @Test
  void executerPurgerConcertsHorsPerimetre_departementSuivi_conserve() {
    utilisateur("u1", "75");
    final UUID suivie = fiche("75", null, null, 40);

    service.executerPurgerConcertsHorsPerimetre();

    assertEquals(List.of(suivie), restantes());
  }

  @Test
  void executerPurgerConcertsHorsPerimetre_observationRecente_conserve() {
    utilisateur("u1", "75");
    final UUID recente = fiche("69", null, null, 2);

    service.executerPurgerConcertsHorsPerimetre();

    assertEquals(List.of(recente), restantes());
  }

  @Test
  void executerPurgerConcertsHorsPerimetre_artisteOuSalleSuivis_conserve() {
    utilisateur("u1", "75");
    final UUID artisteSuivi = artiste();
    final UUID salleSuivie = salle();
    jdbc.update(
        "INSERT INTO user_artistes (user_id, artiste_id, artiste_affiche)"
            + " VALUES ('u1', CAST(:id AS uuid), 'a')",
        Map.of("id", artisteSuivi.toString()));
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES ('u1', CAST(:id AS uuid))",
        Map.of("id", salleSuivie.toString()));
    final UUID parArtiste = fiche("69", null, artisteSuivi, 40);
    final UUID parSalle = fiche("33", salleSuivie, null, 40);
    fiche("13", salle(), artiste(), 40);

    service.executerPurgerConcertsHorsPerimetre();

    assertEquals(
        List.of(parArtiste, parSalle).stream().sorted().toList(),
        restantes().stream().sorted().toList());
  }
}
