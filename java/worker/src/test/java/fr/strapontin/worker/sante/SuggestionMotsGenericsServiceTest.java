/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link SuggestionMotsGenericsService}.
///
/// @since 2.9.0
/// @author Kwaite
class SuggestionMotsGenericsServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private LogsAdminWorker logsAdminWorker;
  private AtomicReference<String> valeurAppConfig;
  private SuggestionMotsGenericsService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    valeurAppConfig = new AtomicReference<>();
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(String.class)))
        .thenAnswer(_ -> valeurAppConfig.get());
    when(jdbc.update(
            contains("INSERT INTO app_config"), org.mockito.ArgumentMatchers.<Map<String, ?>>any()))
        .thenAnswer(
            invocation -> {
              Map<String, ?> params = invocation.getArgument(1);
              valeurAppConfig.set((String) params.get("json"));
              return 1;
            });
    service = new SuggestionMotsGenericsService(jdbc, logsAdminWorker, new ObjectMapper());
  }

  private void stubNoms(List<String> noms) {
    when(jdbc.queryForList(
            contains("FROM salles"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
            eq(String.class)))
        .thenReturn(noms);
  }

  private static List<String> nomsAvecTokenRepete(String token, int occurrences) {
    List<String> noms = new ArrayList<>();
    for (int i = 0; i < occurrences; i++) {
      noms.add(token + " ville" + i);
    }
    return noms;
  }

  @Test
  void suggerer_tokenAuDelaDuSeuil_estSuggereEtJournalise() {
    stubNoms(
        nomsAvecTokenRepete(
            "kiosque", SuggestionMotsGenericsService.SEUIL_OCCURRENCES_MOT_GENERIQUE));

    int nouveaux = service.suggerer();

    assertEquals(1, nouveaux);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-salle"),
            contains("kiosque"),
            org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void suggerer_tokenSousLeSeuil_nEstPasSuggere() {
    stubNoms(
        nomsAvecTokenRepete(
            "guichet", SuggestionMotsGenericsService.SEUIL_OCCURRENCES_MOT_GENERIQUE - 1));

    int nouveaux = service.suggerer();

    assertEquals(0, nouveaux);
    verify(logsAdminWorker, never())
        .inserer(
            anyString(), anyString(), contains("guichet"), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void suggerer_motGeneriqueDejaConnu_nEstJamaisSuggere() {
    stubNoms(
        nomsAvecTokenRepete(
            "theatre", SuggestionMotsGenericsService.SEUIL_OCCURRENCES_MOT_GENERIQUE));

    int nouveaux = service.suggerer();

    assertEquals(0, nouveaux);
    verify(logsAdminWorker, never())
        .inserer(
            anyString(), anyString(), contains("theatre"), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void suggerer_motDejaSuggereAuCyclePrecedent_nEstPasResignale() {
    valeurAppConfig.set("[\"kiosque\"]");
    stubNoms(
        nomsAvecTokenRepete(
            "kiosque", SuggestionMotsGenericsService.SEUIL_OCCURRENCES_MOT_GENERIQUE));

    int nouveaux = service.suggerer();

    assertEquals(0, nouveaux);
    verify(logsAdminWorker, never())
        .inserer(
            anyString(), anyString(), contains("kiosque"), org.mockito.ArgumentMatchers.anyMap());
  }
}
