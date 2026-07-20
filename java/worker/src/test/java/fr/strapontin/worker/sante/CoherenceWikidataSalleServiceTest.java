/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link CoherenceWikidataSalleService} (#1005).
///
/// @since 2.7.5
/// @author Kwaite
class CoherenceWikidataSalleServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private WikidataSalleSource wikidata;
  private LogsAdminWorker logsAdminWorker;
  private CoherenceWikidataSalleService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    wikidata = mock(WikidataSalleSource.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new CoherenceWikidataSalleService(jdbc, wikidata, logsAdminWorker);
  }

  @SuppressWarnings("unchecked")
  private void simulerCandidats(List<SalleAEnrichir> candidats) {
    when(jdbc.query(
            org.mockito.ArgumentMatchers.anyString(),
            any(Map.class),
            any(org.springframework.jdbc.core.RowMapper.class)))
        .thenReturn(candidats);
  }

  @Test
  void verifier_aucunCandidat_neContactePasWikidataEtRetourneZero() {
    simulerCandidats(List.of());

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(wikidata, never()).enrichirLot(anyList());
  }

  @Test
  void verifier_villeCoherente_neJournaliseRien() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", "Lille", null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_villeIncoherente_journaliseEtCompteUneIncoherence() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q428765");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q428765", "Musée des Beaux-Arts de Nancy", "Nancy", null, null)));

    int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Theatre Municipal"),
            any());
  }

  @Test
  void verifier_communeWikidataInconnue_neJournalisePasEtNeCompteRien() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", null, null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_salleSansResultatWikidata_ignoreeSansErreur() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList())).thenReturn(Map.of());

    int resultat = service.verifier();

    assertEquals(0, resultat);
  }
}
