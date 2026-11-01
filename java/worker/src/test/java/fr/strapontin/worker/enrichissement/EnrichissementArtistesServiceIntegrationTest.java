/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.enrichissement.ArtisteAEnrichir;
import fr.strapontin.sources.enrichissement.DeezerArtisteSource;
import fr.strapontin.sources.enrichissement.EnrichissementArtiste;
import fr.strapontin.sources.enrichissement.WikidataArtisteSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
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

/**
 * Tests d'intégration de [EnrichissementArtistesService] avec PostgreSQL réel (Testcontainers) —
 * couvre spécifiquement la rotation hebdomadaire par {@code jour_batch}, symétrique à {@link
 * EnrichissementSallesServiceIntegrationTest}.
 *
 * @since 2.7.3
 * @author Kwaite
 */
class EnrichissementArtistesServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean private WikidataArtisteSource wikidataArtisteSource;
  @MockitoBean private DeezerArtisteSource deezerArtisteSource;

  @MockitoBean private TicketmasterSource ticketmasterSource;

  @Autowired private EnrichissementArtistesService service;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBaseEtStubberSources() {
    jdbc.update("DELETE FROM artistes", Map.of());
    when(wikidataArtisteSource.enrichirLot(any())).thenReturn(Map.of());
    when(deezerArtisteSource.enrichirLot(any())).thenReturn(Map.of());
    when(wikidataArtisteSource.nom()).thenReturn("wikidata");
    when(deezerArtisteSource.nom()).thenReturn("deezer");
    when(wikidataArtisteSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
    when(deezerArtisteSource.preleverBilan()).thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
  }

  private UUID insererArtiste(final String nom, final String jourBatchExpression) {
    return jdbc.queryForObject(
        "INSERT INTO artistes (nom, nom_norm, jour_batch) VALUES (:nom, :norm, "
            + jourBatchExpression
            + ") RETURNING id",
        Map.of("nom", nom, "norm", nom.toLowerCase()),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void enrichir_artisteDuJourBatchCourant_estCandidat() {
    final UUID artisteAujourdhui =
        insererArtiste("Artiste Aujourdhui", "EXTRACT(ISODOW FROM CURRENT_DATE)");

    service.enrichir();

    final ArgumentCaptor<List<ArtisteAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(wikidataArtisteSource).enrichirLot(captor.capture());
    final List<UUID> idsTraites = captor.getValue().stream().map(ArtisteAEnrichir::id).toList();
    assertTrue(idsTraites.contains(artisteAujourdhui));
    assertEquals(1, idsTraites.size());
  }

  @Test
  void enrichir_artisteDunAutreJourBatch_nEstPasCandidat() {
    insererArtiste("Artiste Autre Jour", "((EXTRACT(ISODOW FROM CURRENT_DATE)::int % 7) + 1)");

    service.enrichir();

    verify(wikidataArtisteSource, never()).enrichirLot(any());
  }

  private Object[] alias(final UUID artisteId) {
    return jdbc.queryForObject(
        "SELECT alias_wikidata FROM artistes WHERE id = CAST(:id AS uuid)",
        Map.of("id", artisteId.toString()),
        (rs, _) -> rs.getArray(1) == null ? null : (Object[]) rs.getArray(1).getArray());
  }

  // Artistes identifiés avant l'ajout des alias : jamais relus, aucun alias (« -M- » pour
  // Matthieu Chedid) n'était connu, et les noms de scène n'étaient jamais reconnus.
  @Test
  void enrichir_artisteIdentifieSansAliasLus_estRelu() {
    final UUID m = insererArtiste("-M-", "EXTRACT(ISODOW FROM CURRENT_DATE)");
    final UUID sansAlias = insererArtiste("Sans Alias", "EXTRACT(ISODOW FROM CURRENT_DATE)");
    jdbc.update(
        "UPDATE artistes SET wikidata_id = CASE WHEN id = CAST(:m AS uuid) THEN 'Q2365459'"
            + " ELSE 'Q1' END",
        Map.of("m", m.toString()));
    when(wikidataArtisteSource.enrichirLot(any()))
        .thenReturn(
            Map.of(
                m,
                new EnrichissementArtiste("Q2365459", null, null, null, List.of("Matthieu Chedid")),
                sansAlias,
                new EnrichissementArtiste("Q1", null, null, null, List.of())));

    service.enrichir();

    assertEquals(List.of("Matthieu Chedid"), List.of(alias(m)));
    assertEquals(0, alias(sansAlias).length);
  }
}
