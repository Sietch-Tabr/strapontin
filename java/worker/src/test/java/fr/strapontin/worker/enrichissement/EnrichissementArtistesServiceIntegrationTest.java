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

import fr.strapontin.sources.ArtisteAEnrichir;
import fr.strapontin.sources.DeezerArtisteSource;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.WikidataArtisteSource;
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

/// Tests d'intégration de [EnrichissementArtistesService] avec PostgreSQL réel (Testcontainers) —
/// couvre spécifiquement la rotation hebdomadaire par {@code jour_batch} (#994), symétrique à
/// {@link EnrichissementSallesServiceIntegrationTest}.
///
/// @since 2.7.3
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class EnrichissementArtistesServiceIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean private WikidataArtisteSource wikidataArtisteSource;
  @MockitoBean private DeezerArtisteSource deezerArtisteSource;

  @MockitoBean
  @SuppressWarnings("unused")
  private TicketmasterSource ticketmasterSource;

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

  private UUID insererArtiste(String nom, String jourBatchExpression) {
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
    UUID artisteAujourdhui =
        insererArtiste("Artiste Aujourdhui", "EXTRACT(ISODOW FROM CURRENT_DATE)");

    service.enrichir();

    ArgumentCaptor<List<ArtisteAEnrichir>> captor = ArgumentCaptor.forClass(List.class);
    verify(wikidataArtisteSource).enrichirLot(captor.capture());
    List<UUID> idsTraites = captor.getValue().stream().map(ArtisteAEnrichir::id).toList();
    assertTrue(idsTraites.contains(artisteAujourdhui));
    assertEquals(1, idsTraites.size());
  }

  @Test
  void enrichir_artisteDunAutreJourBatch_nEstPasCandidat() {
    insererArtiste("Artiste Autre Jour", "((EXTRACT(ISODOW FROM CURRENT_DATE)::int % 7) + 1)");

    service.enrichir();

    verify(wikidataArtisteSource, never()).enrichirLot(any());
  }
}
