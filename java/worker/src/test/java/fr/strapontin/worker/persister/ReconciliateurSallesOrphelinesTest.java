/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link ReconciliateurSallesOrphelines}.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class ReconciliateurSallesOrphelinesTest {

  @SuppressWarnings("unchecked")
  private static void simulerCandidatSalle(
      final NamedParameterJdbcTemplate jdbc, final UUID salleId) {
    // candidats chargés en une seule requête (jdbc.query + RowMapper), pas une par orphelin —
    // seule la requête "FROM salles" doit répondre ainsi, jamais celle de recherche de doublon.
    when(jdbc.query(
            argThat((String sql) -> sql != null && sql.contains("FROM salles")),
            anyMap(),
            any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              final RowMapper<Object> mapper = invocation.getArgument(2);
              final ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id", UUID.class)).thenReturn(salleId);
              when(rs.getString("nom_norm")).thenReturn("zenith toulouse metropole");
              when(rs.getString("dept_effectif")).thenReturn("31");
              return List.of(mapper.mapRow(rs, 0));
            });
  }

  // Un concert sans salle_id (TM a omis venue.id, aucune salle suffisamment proche au moment de
  // l'insertion) doit pouvoir être résolu plus tard si une salle correspondante apparaît entre
  // temps via une autre source.
  @Test
  @SuppressWarnings("unchecked")
  void retenterSallesOrphelines_salleApparueEntreTemps_resoutLeConcert() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final FusionEvenementsArtisteService fusion = mock(FusionEvenementsArtisteService.class);
    final ReconciliateurSallesOrphelines reconciliateur =
        new ReconciliateurSallesOrphelines(jdbc, fusion);
    final UUID concertId = UUID.randomUUID();
    final UUID salleId = UUID.randomUUID();

    final Map<String, Object> orphelin = new HashMap<>();
    orphelin.put("id", concertId);
    orphelin.put("salle", "Le Zenith Toulouse Metropole");
    orphelin.put("dept", "31");
    orphelin.put("artiste_norm", "daft punk");
    orphelin.put("date", java.time.LocalDate.of(2026, 11, 17));
    orphelin.put("heure", java.time.LocalTime.of(20, 0));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(orphelin));

    simulerCandidatSalle(jdbc, salleId);
    // aucun événement en conflit déjà établi (branche UPDATE simple).
    when(jdbc.query(
            argThat((String sql) -> sql != null && sql.contains("FROM evenements")),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of());

    final int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(1, resolus);
    verify(jdbc)
        .update(
            anyString(),
            argThat(
                (Map<String, Object> params) ->
                    salleId.toString().equals(params.get("salleId"))
                        && concertId.equals(params.get("id"))));
    verify(fusion, never()).fusionnerUnePaire(any(), any());
  }

  // La salle candidate ferait doublon (même artiste_norm/date/heure) avec un événement déjà
  // établi — l'UPDATE violerait uq_evenements (incident réel v3.0.0), la fusion doit prendre
  // le relais plutôt que de planter le cycle nocturne entier.
  @Test
  @SuppressWarnings("unchecked")
  void retenterSallesOrphelines_salleCandidateFeraitDoublon_fusionneAuLieuDePlanter() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final FusionEvenementsArtisteService fusion = mock(FusionEvenementsArtisteService.class);
    final ReconciliateurSallesOrphelines reconciliateur =
        new ReconciliateurSallesOrphelines(jdbc, fusion);
    final UUID concertId = UUID.randomUUID();
    final UUID salleId = UUID.randomUUID();
    final UUID evenementExistantId = UUID.randomUUID();

    final Map<String, Object> orphelin = new HashMap<>();
    orphelin.put("id", concertId);
    orphelin.put("salle", "Le Zenith Toulouse Metropole");
    orphelin.put("dept", "31");
    orphelin.put("artiste_norm", "la grande sophie");
    orphelin.put("date", java.time.LocalDate.of(2026, 11, 17));
    orphelin.put("heure", java.time.LocalTime.of(20, 0));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(orphelin));

    simulerCandidatSalle(jdbc, salleId);
    when(jdbc.query(
            argThat((String sql) -> sql != null && sql.contains("FROM evenements")),
            anyMap(),
            any(RowMapper.class)))
        .thenReturn(List.of(evenementExistantId));

    final int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(1, resolus);
    verify(fusion).fusionnerUnePaire(evenementExistantId, concertId);
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void retenterSallesOrphelines_aucunOrphelin_neQueryPasLesCandidats() {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final FusionEvenementsArtisteService fusion = mock(FusionEvenementsArtisteService.class);
    final ReconciliateurSallesOrphelines reconciliateur =
        new ReconciliateurSallesOrphelines(jdbc, fusion);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(0, resolus);
  }
}
