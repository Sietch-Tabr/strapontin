/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.enrichissement.ClientNominatim;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.LogsAdminWorker;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Alignement de la ville d'une salle sur la commune de sa position, sur base réelle.
 *
 * @since 3.1.2
 * @author Kwaite
 */
class AlignementCommuneSallesIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private ClientNominatim nominatim;
  private AlignementCommuneSalles alignement;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM logs_admin", Map.of());
    nominatim = mock(ClientNominatim.class);
    alignement = new AlignementCommuneSalles(jdbc, nominatim, logsAdminWorker);
  }

  private UUID salle(final String nom, final String ville, final String wikidataId) {
    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm, dept, ville, latitude, longitude,"
                + " origine_position, wikidata_id) VALUES (:cle, :nom, :cle, '51', :ville,"
                + " 49.2397, 3.9875, 'sources', :wikidata) RETURNING id",
            new MapSqlParameterSource()
                .addValue("cle", nom.toLowerCase())
                .addValue("nom", nom)
                .addValue("ville", ville)
                .addValue("wikidata", wikidataId),
            UUID.class);
    jdbc.update(
        "INSERT INTO evenements (artiste_norm, date, salle_norm, salle_id)"
            + " VALUES ('nicolas canteloup', :date, 'x', CAST(:salle AS uuid))",
        new MapSqlParameterSource()
            .addValue("date", LocalDate.now().plusDays(10))
            .addValue("salle", id.toString()));
    return id;
  }

  private String ville(final UUID id) {
    return jdbc.queryForObject(
        "SELECT ville FROM salles WHERE id = CAST(:id AS uuid)",
        Map.of("id", id.toString()),
        String.class);
  }

  private static ClientNominatim.ResultatCommune commune(final String nom, final String cp) {
    return new ClientNominatim.ResultatCommune(Optional.of(nom), Optional.of(cp), true);
  }

  @Test
  void aligner_communeDifferenteDuMemeDepartement_corrigeLaVilleEtLaTrace() {
    final UUID leK = salle("Le K", "Reims", null);
    when(nominatim.communeDe(any())).thenReturn(commune("Tinqueux", "51430"));

    assertEquals(1, alignement.aligner());

    assertEquals("Tinqueux", ville(leK));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM logs_admin WHERE message LIKE '%Reims → Tinqueux%'",
            Map.of(), Long.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT commune_verifiee_le FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", leK.toString()),
            OffsetDateTime.class));
  }

  @Test
  void aligner_communeIdentiqueAuxAccentsEtALaCasseSpres_neChangeRien() {
    final UUID salle = salle("Salle X", "Saint-Étienne", null);
    when(nominatim.communeDe(any())).thenReturn(commune("saint-etienne", "51100"));

    assertEquals(0, alignement.aligner());
    assertEquals("Saint-Étienne", ville(salle));
  }

  @Test
  void aligner_communeDUnAutreDepartement_neChangeRien() {
    final UUID salle = salle("Salle Y", "Reims", null);
    when(nominatim.communeDe(any())).thenReturn(commune("Charleville", "08000"));

    assertEquals(0, alignement.aligner());
    assertEquals("Reims", ville(salle));
  }

  @Test
  void aligner_salleAvecElementWikidata_neSeraPasInterrogee() {
    salle("Salle Z", "Reims", "Q123");

    assertEquals(0, alignement.aligner());
    verify(nominatim, never()).communeDe(any());
  }

  @Test
  void aligner_nominatimEnEchec_neCorrigeRienEtNeDateAucuneVerification() {
    final UUID salle = salle("Salle W", "Reims", null);
    when(nominatim.communeDe(any()))
        .thenReturn(new ClientNominatim.ResultatCommune(Optional.empty(), Optional.empty(), false));

    assertEquals(0, alignement.aligner());
    assertEquals("Reims", ville(salle));
    assertNull(
        jdbc.queryForObject(
            "SELECT commune_verifiee_le FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.toString()),
            OffsetDateTime.class));
  }
}
