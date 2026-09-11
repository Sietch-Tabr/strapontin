/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

  // Un concert sans salle_id (TM a omis venue.id, aucune salle suffisamment proche au moment de
  // l'insertion) doit pouvoir être résolu plus tard si une salle correspondante apparaît entre
  // temps via une autre source.
  @Test
  @SuppressWarnings("unchecked")
  void retenterSallesOrphelines_salleApparueEntreTemps_resoutLeConcert() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSallesOrphelines reconciliateur = new ReconciliateurSallesOrphelines(jdbc);
    UUID concertId = UUID.randomUUID();
    UUID salleId = UUID.randomUUID();

    Map<String, Object> orphelin = new HashMap<>();
    orphelin.put("id", concertId);
    orphelin.put("salle", "Le Zenith Toulouse Metropole");
    orphelin.put("dept", "31");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(orphelin));

    // candidats chargés en une seule requête (jdbc.query + RowMapper), pas une par orphelin.
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(2);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id", UUID.class)).thenReturn(salleId);
              when(rs.getString("nom_norm")).thenReturn("zenith toulouse metropole");
              when(rs.getString("dept_effectif")).thenReturn("31");
              return List.of(mapper.mapRow(rs, 0));
            });

    int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(1, resolus);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> params) ->
                    salleId.toString().equals(params.get("salleId"))
                        && concertId.equals(params.get("id"))));
  }

  @Test
  void retenterSallesOrphelines_aucunOrphelin_neQueryPasLesCandidats() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ReconciliateurSallesOrphelines reconciliateur = new ReconciliateurSallesOrphelines(jdbc);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    int resolus = reconciliateur.retenterSallesOrphelines();

    assertEquals(0, resolus);
  }
}
