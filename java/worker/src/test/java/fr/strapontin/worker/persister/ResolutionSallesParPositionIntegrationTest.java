/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.strapontin.core.PositionLieu;
import fr.strapontin.sources.ClientNominatim;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Résolution des salles par position du lieu ({@link ResolveurSallePosition}) et simulation de
 * son effet sur les données existantes ({@link SimulationResolutionSalles}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
class ResolutionSallesParPositionIntegrationTest extends AbstractWorkerIntegrationTest {

  // Théâtre Montparnasse et Théâtre de Poche Montparnasse : mitoyens, ~40 m.
  private static final PositionLieu MONTPARNASSE = new PositionLieu(48.8432, 2.3236, "75014");
  private static final PositionLieu POCHE = new PositionLieu(48.8435, 2.3240, "75006");
  private static final PositionLieu VITTEL = new PositionLieu(48.2025, 5.9480, "88800");
  private static final PositionLieu PERPIGNAN = new PositionLieu(42.7025, 2.8946, "66000");

  @MockitoBean TicketmasterSource ticketmasterSource;
  @MockitoBean ClientNominatim clientNominatim;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private SimulationResolutionSalles simulation;

  private ResolveurSallePosition parPosition;

  @BeforeEach
  void setUp() {
    parPosition = new ResolveurSallePosition(jdbc);
  }

  private UUID creerSalle(final String nom, final String dept, final PositionLieu position) {
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO salles (id, salle_cle, nom, nom_norm, dept, latitude, longitude)"
            + " VALUES (CAST(:id AS uuid), :cle, :nom, :nomNorm, :dept, :lat, :lon)",
        new MapSqlParameterSource()
            .addValue("id", id.toString())
            .addValue("cle", nom.toLowerCase() + " " + dept)
            .addValue("nom", nom)
            .addValue("nomNorm", nom.toLowerCase())
            .addValue("dept", dept)
            .addValue("lat", position.latitude())
            .addValue("lon", position.longitude()));
    return id;
  }

  private void mapper(final UUID salle, final String identifiant, final PositionLieu position) {
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe, latitude, longitude,"
            + " code_postal) VALUES (CAST(:id AS uuid), 'eventim', :identifiant, :lat, :lon, :cp)",
        new MapSqlParameterSource()
            .addValue("id", salle.toString())
            .addValue("identifiant", identifiant)
            .addValue("lat", position.latitude())
            .addValue("lon", position.longitude())
            .addValue("cp", position.codePostal()));
  }

  @Test
  void chercher_memeLieuNomDoublon_retientLaSalle() {
    final UUID montparnasse = creerSalle("Theatre Montparnasse", "75", MONTPARNASSE);

    final ResolveurSallePosition.SalleProche proche =
        parPosition.chercher("Théâtre Montparnasse", "75", MONTPARNASSE);

    assertEquals(montparnasse, proche.id());
  }

  @Test
  void chercher_salleMitoyenneNomDifferent_neLaRetientPas() {
    creerSalle("Theatre Montparnasse", "75", MONTPARNASSE);

    assertNull(parPosition.chercher("Théâtre de Poche Montparnasse", "75", POCHE));
  }

  @Test
  void chercher_homonymeDUneAutreVille_neLeRetientPas() {
    creerSalle("Palais des Congres", "88", VITTEL);

    assertNull(parPosition.chercher("Palais des Congrès", "66", PERPIGNAN));
  }

  @Test
  void incompatible_salleGeolocaliseeLoinDuLieu_vrai() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);

    assertTrue(parPosition.incompatible(vittel, PERPIGNAN));
    assertFalse(parPosition.incompatible(vittel, VITTEL));
    assertFalse(parPosition.incompatible(vittel, PositionLieu.INCONNUE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void simuler_salleContamineeParUnLieuDistant_laClasseIncompatible() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);
    mapper(vittel, "internal:palais des congres@88800", VITTEL);
    mapper(vittel, "internal:palais des congres@66000", PERPIGNAN);

    final Map<String, Object> rapport = simulation.simuler();

    final Map<String, Integer> effectifs = (Map<String, Integer>) rapport.get("effectifs");
    assertEquals(1, effectifs.get("conforme"));
    assertEquals(1, effectifs.get("salleIncompatible"));
  }

  @Test
  void resolution_parPositionActivee_homonymeDistantEcarteEtSalleCreeeSansGeocodage() {
    final UUID vittel = creerSalle("Palais des Congres", "88", VITTEL);
    final ResolveurSalleParConcert resolveur = new ResolveurSalleParConcert(jdbc, clientNominatim);
    resolveur.setResolutionParPosition(true);
    final ValeurConcert concert =
        FabriqueValeurConcert.nouveau()
            .artiste("Tina Arena")
            .salle("Palais des Congres")
            .dept("66")
            .source("eventim")
            .salleSourceId("eventim:internal:palais des congres@66000")
            .position(PERPIGNAN)
            .construire();

    final UUID salle = resolveur.upsertSalleSource(concert);

    assertNotEquals(vittel, salle);
    // Créée sans géocodage du nom : l'enregistrement des positions y posera celle de la source.
    verifyNoInteractions(clientNominatim);
  }
}
