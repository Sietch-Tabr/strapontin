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
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/// Tests d'intégration de [EnrichissementSallesService] avec PostgreSQL réel (Testcontainers) —
/// couvre spécifiquement la rotation hebdomadaire par {@code jour_batch}, qui ne peut être vérifiée
/// qu'avec une vraie base (l'expression SQL {@code EXTRACT(ISODOW FROM CURRENT_DATE)} n'est jamais
/// évaluée avec un {@code NamedParameterJdbcTemplate} mocké).
///
/// @since 2.7.3
/// @author Kwaite
class EnrichissementSallesServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private OverpassSalleSource overpassSalleSource;
  @MockitoBean private WikidataSalleSource wikidataSalleSource;

  @MockitoBean private TicketmasterSource ticketmasterSource;

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

  // #1188 : salles.dept n'est jamais mis à jour après création — une salle candidate sans dept
  // connu doit hériter du département d'un concert déjà rattaché, seul signal de localisation
  // disponible pour écarter un homonyme Wikidata d'une autre ville.
  @Test
  @SuppressWarnings("unchecked")
  void enrichir_salleSansDeptAvecConcertRattache_heriteDuDeptDuConcert() {
    UUID salleId = insererSalle("Salle Sans Dept", "EXTRACT(ISODOW FROM CURRENT_DATE)");
    UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, dept, salle_id)"
                + " VALUES ('artiste', CURRENT_DATE + 30, 'salle sans dept', '87',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), 'Artiste',"
            + " 'Salle Sans Dept')",
        Map.of("id", evenementId.toString()));

    service.enrichir();

    ArgumentCaptor<List<SalleAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(overpassSalleSource).enrichirLot(captor.capture());
    SalleAEnrichir candidat = captor.getValue().getFirst();
    assertEquals("87", candidat.dept());
  }
}
