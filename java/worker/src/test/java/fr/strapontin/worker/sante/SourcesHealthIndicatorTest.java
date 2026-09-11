/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.core.QuotaTM;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link SourcesHealthIndicator}.
 *
 * @since 2.7.0
 * @author Kwaite
 */
class SourcesHealthIndicatorTest {

  private GestionQuotaTM gestionQuotaTM;
  private NamedParameterJdbcTemplate jdbc;
  private SourcesHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    gestionQuotaTM = mock(GestionQuotaTM.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    indicator = new SourcesHealthIndicator(gestionQuotaTM, jdbc);
  }

  @Test
  void health_quotaSuffisantEtAucuneErreur_retourneUp() {
    when(gestionQuotaTM.lireQuota())
        .thenReturn(Optional.of(new QuotaTM(4000, 5000, 0L, "2026-07-17T00:00:00Z")));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final Health health = indicator.health();

    assertEquals(Health.up().build().getStatus(), health.getStatus());
    assertEquals(4000, health.getDetails().get("quotaTmDispo"));
  }

  @Test
  void health_quotaEpuise_retourneDown() {
    when(gestionQuotaTM.lireQuota())
        .thenReturn(Optional.of(new QuotaTM(10, 5000, 0L, "2026-07-17T00:00:00Z")));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final Health health = indicator.health();

    assertEquals(Health.down().build().getStatus(), health.getStatus());
    assertEquals("épuisé", health.getDetails().get("quotaTm"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void health_sourceEnErreur_retourneDownAvecDetail() {
    when(gestionQuotaTM.lireQuota())
        .thenReturn(Optional.of(new QuotaTM(4000, 5000, 0L, "2026-07-17T00:00:00Z")));
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("type_job", "fetch:region");
    ligne.put("erreur", "Worker inaccessible");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligne));

    final Health health = indicator.health();

    assertEquals(Health.down().build().getStatus(), health.getStatus());
    final Map<String, Object> sourcesEnErreur =
        (Map<String, Object>) health.getDetails().get("sourcesEnErreur");
    assertEquals("Worker inaccessible", sourcesEnErreur.get("fetch:region"));
  }

  @Test
  void health_aucunQuotaEnBase_retourneUpSansDetailQuota() {
    when(gestionQuotaTM.lireQuota()).thenReturn(Optional.empty());
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    final Health health = indicator.health();

    assertEquals(Health.up().build().getStatus(), health.getStatus());
    assertEquals(null, health.getDetails().get("quotaTmDispo"));
  }
}
