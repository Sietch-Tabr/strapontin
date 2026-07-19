/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.OverpassSalleSource;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.WikidataSalleSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Tests d'intégration de [EnrichissementSallesService] avec PostgreSQL réel (Testcontainers) —
/// couvre spécifiquement la rotation hebdomadaire par {@code jour_batch} (#994), qui ne peut être
/// vérifiée qu'avec une vraie base (l'expression SQL {@code EXTRACT(ISODOW FROM CURRENT_DATE)}
/// n'est jamais évaluée avec un {@code NamedParameterJdbcTemplate} mocké).
///
/// @since 2.7.3
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class EnrichissementSallesServiceIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean private OverpassSalleSource overpassSalleSource;
  @MockitoBean private WikidataSalleSource wikidataSalleSource;

  @MockitoBean
  @SuppressWarnings("unused")
  private TicketmasterSource ticketmasterSource;

  @Autowired private EnrichissementSallesService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBaseEtStubberSources() {
    jdbc.update("DELETE FROM salles", Map.of());
    when(overpassSalleSource.enrichirLot(any())).thenReturn(Map.of());
    when(wikidataSalleSource.enrichirLot(any())).thenReturn(Map.of());
    when(overpassSalleSource.nom()).thenReturn("overpass");
    when(wikidataSalleSource.nom()).thenReturn("wikidata");
    when(overpassSalleSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
    when(wikidataSalleSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
  }

  private UUID insererSalle(String nom, String jourBatchExpression) {
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, jour_batch)"
            + " VALUES (:cle, :nom, :norm, "
            + jourBatchExpression
            + ") RETURNING id",
        Map.of("cle", nom.toLowerCase(), "nom", nom, "norm", nom.toLowerCase()),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void enrichir_salleDuJourBatchCourant_estCandidate() {
    UUID salleAujourdhui = insererSalle("Salle Aujourdhui", "EXTRACT(ISODOW FROM CURRENT_DATE)");

    service.enrichir();

    ArgumentCaptor<List<SalleAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(overpassSalleSource).enrichirLot(captor.capture());
    List<UUID> idsTraites = captor.getValue().stream().map(SalleAEnrichir::id).toList();
    assertTrue(idsTraites.contains(salleAujourdhui));
    assertEquals(1, idsTraites.size());
  }

  @Test
  void enrichir_salleDunAutreJourBatch_nEstPasCandidate() {
    // ISODOW + 1 mod 7 : toujours un jour différent d'aujourd'hui, quel que soit le jour réel du
    // run (évite un test qui échouerait un jour sur sept si la valeur était figée).
    insererSalle("Salle Autre Jour", "((EXTRACT(ISODOW FROM CURRENT_DATE)::int % 7) + 1)");

    service.enrichir();

    verify(overpassSalleSource, org.mockito.Mockito.never()).enrichirLot(any());
  }
}
