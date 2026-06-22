/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.QuotaTM;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClient;

/// Tests unitaires pour {@link SourcesService}.
///
/// @since 2.3.0
/// @author Kwaite
class SourcesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private SourcesService sourcesService;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    sourcesService = new SourcesService(jdbc, new ObjectMapper(), mock(RestClient.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_sansQuota_retourneSourcesAvecEtatOk() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(java.sql.Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.query(
            anyString(),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    Map<String, Object> resultat = sourcesService.lireSources();

    assertNotNull(resultat.get("sources"));
    assertNotNull(resultat.get("salles"));
    List<StatutSource> sources = (List<StatutSource>) resultat.get("sources");
    assertEquals(4, sources.size());

    StatutSource tm = sources.get(0);
    assertEquals("ticketmaster", tm.id());
    assertEquals("ok", tm.etatQuota());
    assertNull(tm.quotaTM());

    StatutSource digitick = sources.get(3);
    assertEquals("digitick", digitick.id());
    assertEquals(0, digitick.nbSallesConfigurees());
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_avecQuotaCritique_retourneEtatCritique() throws Exception {
    QuotaTM quota = new QuotaTM(90, 1000, 0L, "2026-06-19T10:00:00Z");
    String quotaJson = new ObjectMapper().writeValueAsString(quota);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of(quotaJson));
    when(jdbc.queryForObject(anyString(), anyMap(), eq(java.sql.Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
    when(jdbc.query(
            anyString(),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    Map<String, Object> resultat = sourcesService.lireSources();
    List<StatutSource> sources = (List<StatutSource>) resultat.get("sources");

    StatutSource tm = sources.get(0);
    assertNotNull(tm.quotaTM());
    assertEquals(90, tm.quotaTM().dispo());
    assertEquals("critique", tm.etatQuota());
  }

  @Test
  void mettreAJourDigitickSlug_slugValide_upsert() {
    UUID uuid = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    sourcesService.mettreAJourDigitickSlug(uuid, "lempreinte");

    verify(jdbc)
        .update(
            argThat((String sql) -> sql.contains("INSERT INTO salle_sources")),
            argThat((Map<String, ?> params) -> "lempreinte".equals(params.get("slug"))));
  }

  @Test
  void mettreAJourDigitickSlug_slugVide_supprime() {
    UUID uuid = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    sourcesService.mettreAJourDigitickSlug(uuid, "");

    verify(jdbc)
        .update(argThat((String sql) -> sql.contains("DELETE FROM salle_sources")), anyMap());
  }

  @Test
  void mettreAJourDigitickSlug_slugNull_supprime() {
    UUID uuid = UUID.randomUUID();
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    sourcesService.mettreAJourDigitickSlug(uuid, null);

    verify(jdbc)
        .update(argThat((String sql) -> sql.contains("DELETE FROM salle_sources")), anyMap());
  }

  @Test
  void testerDigitickSlug_slugVide_retourneFalse() {
    assertFalse(sourcesService.testerDigitickSlug(""));
    assertFalse(sourcesService.testerDigitickSlug(null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_requetesSalles_utilisentColonneId_pasSlleUuid() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(java.sql.Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbc.query(
            sqlCaptor.capture(),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of());

    sourcesService.lireSources();

    sqlCaptor
        .getAllValues()
        .forEach(
            sql ->
                assertFalse(
                    sql.contains("salle_uuid"),
                    "Requête SQL référence encore salle_uuid (colonne supprimée en V24) : " + sql));
  }

  @Test
  void testerDigitickSlug_erreurReseau_retourneFalse() {
    RestClient restClientEchoue = mock(RestClient.class);
    RestClient.RequestHeadersUriSpec<?> spec = mock(RestClient.RequestHeadersUriSpec.class);
    when(restClientEchoue.head()).thenThrow(new RuntimeException("timeout"));
    SourcesService service = new SourcesService(jdbc, new ObjectMapper(), restClientEchoue);

    assertFalse(service.testerDigitickSlug("lempreinte"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void lireSources_avecSalles_retourneSalles() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), anyMap(), eq(java.sql.Timestamp.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(2);

    UUID uuid = UUID.randomUUID();
    when(jdbc.query(
            anyString(),
            anyMap(),
            org.mockito.ArgumentMatchers
                .<org.springframework.jdbc.core.RowMapper<SalleSourceAdmin>>any()))
        .thenReturn(List.of(new SalleSourceAdmin(uuid, "L'Olympia", null)));

    Map<String, Object> resultat = sourcesService.lireSources();
    List<SalleSourceAdmin> salles = (List<SalleSourceAdmin>) resultat.get("salles");

    assertEquals(1, salles.size());
    assertEquals("L'Olympia", salles.get(0).nom());
    assertNull(salles.get(0).digitickSlug());

    List<StatutSource> sources = (List<StatutSource>) resultat.get("sources");
    StatutSource digitick = sources.get(3);
    assertEquals(2, digitick.nbSallesConfigurees());
  }
}
