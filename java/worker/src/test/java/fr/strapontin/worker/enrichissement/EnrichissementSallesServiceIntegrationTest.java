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

import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.NominatimSalleSource;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests d'intégration de [EnrichissementSallesService] avec PostgreSQL réel (Testcontainers) —
 * couvre spécifiquement la rotation hebdomadaire par {@code jour_batch}, qui ne peut être vérifiée
 * qu'avec une vraie base (l'expression SQL {@code EXTRACT(ISODOW FROM CURRENT_DATE)} n'est jamais
 * évaluée avec un {@code NamedParameterJdbcTemplate} mocké).
 *
 * @since 2.7.3
 * @author Kwaite
 */
class EnrichissementSallesServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private NominatimSalleSource nominatimSalleSource;
  @MockitoBean private WikidataSalleSource wikidataSalleSource;

  @MockitoBean private TicketmasterSource ticketmasterSource;

  @Autowired private EnrichissementSallesService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBaseEtStubberSources() {
    jdbc.update("DELETE FROM salles", Map.of());
    when(nominatimSalleSource.enrichirLot(any())).thenReturn(Map.of());
    when(wikidataSalleSource.enrichirLot(any())).thenReturn(Map.of());
    when(nominatimSalleSource.nom()).thenReturn("nominatim");
    when(wikidataSalleSource.nom()).thenReturn("wikidata");
    when(nominatimSalleSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
    when(wikidataSalleSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
  }

  private UUID insererSalle(final String nom, final String jourBatchExpression) {
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
    final UUID salleAujourdhui =
        insererSalle("Salle Aujourdhui", "EXTRACT(ISODOW FROM CURRENT_DATE)");

    service.enrichir();

    final ArgumentCaptor<List<SalleAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(nominatimSalleSource).enrichirLot(captor.capture());
    final List<UUID> idsTraites = captor.getValue().stream().map(SalleAEnrichir::id).toList();
    assertTrue(idsTraites.contains(salleAujourdhui));
    assertEquals(1, idsTraites.size());
  }

  @Test
  void enrichir_salleDunAutreJourBatch_nEstPasCandidate() {
    // ISODOW + 1 mod 7 : toujours un jour différent d'aujourd'hui, quel que soit le jour réel du
    // run (évite un test qui échouerait un jour sur sept si la valeur était figée).
    insererSalle("Salle Autre Jour", "((EXTRACT(ISODOW FROM CURRENT_DATE)::int % 7) + 1)");

    service.enrichir();

    verify(nominatimSalleSource, org.mockito.Mockito.never()).enrichirLot(any());
  }

  // #1188 : salles.dept n'est jamais mis à jour après création — une salle candidate sans dept
  // connu doit hériter du département d'un concert déjà rattaché, seul signal de localisation
  // disponible pour écarter un homonyme Wikidata d'une autre ville.
  @Test
  @SuppressWarnings("unchecked")
  void enrichir_salleSansDeptAvecConcertRattache_heriteDuDeptDuConcert() {
    final UUID salleId = insererSalle("Salle Sans Dept", "EXTRACT(ISODOW FROM CURRENT_DATE)");
    final UUID evenementId =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, dept, salle_id)"
                + " VALUES ('artiste', CURRENT_DATE + 30, 'salle sans dept', '87',"
                + " CAST(:salleId AS uuid)) RETURNING id",
            new MapSqlParameterSource().addValue("salleId", salleId.toString()),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), 'Artiste',"
            + " 'Salle Sans Dept')",
        Map.of("id", evenementId.toString()));

    service.enrichir();

    final ArgumentCaptor<List<SalleAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(nominatimSalleSource).enrichirLot(captor.capture());
    final SalleAEnrichir candidat = captor.getValue().getFirst();
    assertEquals("87", candidat.dept());
  }

  // Source sans coordonnées (Nominatim ne transmet que les étiquettes OSM) : le paramètre
  // nul de latitude, non typé, faisait échouer toute la réconciliation nocturne.
  @Test
  void enrichir_enrichissementSansCoordonnees_estPersiste() {
    final UUID salleId = insererSalle("La Cigale", "EXTRACT(ISODOW FROM CURRENT_DATE)");
    when(nominatimSalleSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                salleId,
                new EnrichissementSalle("Q3207410", null, null, "https://lacigale.fr/", null)));

    service.enrichir();

    assertEquals(
        "Q3207410",
        jdbc.queryForObject(
            "SELECT wikidata_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            String.class));
  }

  private long alertesTauxBas() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM logs_admin WHERE niveau = 'warn'"
            + " AND message LIKE 'enrichissement:salles — taux de réussite%'",
        Map.of(), Long.class);
  }

  // Les salles restant à enrichir sont les cas difficiles : l'alerte tombait chaque nuit.
  @Test
  void enrichir_tauxBasDejaSignaleCetteSemaine_neRepetePasLAlerte() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
    for (int i = 0; i < 25; i++) {
      insererSalle("Salle Difficile " + i, "EXTRACT(ISODOW FROM CURRENT_DATE)");
    }
    jdbc.update(
        "INSERT INTO logs_admin (ts, niveau, source, message) VALUES (now() - interval '2 days',"
            + " 'warn', 'reconciliation-nocturne',"
            + " 'enrichissement:salles — taux de réussite chronique trop bas (0/25)')",
        Map.of());

    service.enrichir();

    assertEquals(1L, alertesTauxBas());
  }
}
