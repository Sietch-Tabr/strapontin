/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ChangelogServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private ChangelogService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    service = new ChangelogService(jdbc);
  }

  @Test
  void estNonVu_versionVueDifferente_retourneVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("2.6.2"));

    assertTrue(service.estNonVu("u1", "2.6.4"));
  }

  @Test
  void estNonVu_versionVueIdentique_retourneFaux() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("2.6.4"));

    assertFalse(service.estNonVu("u1", "2.6.4"));
  }

  @Test
  void estNonVu_jamaisVue_retourneVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

    assertTrue(service.estNonVu("u1", "2.6.4"));
  }

  @Test
  void marquerVue_ecritLaVersionCouranteDynamique() {
    service.marquerVue("u1", "2.6.4");

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, ?> params) -> "2.6.4".equals(params.get("v"))));
  }

  @Test
  void marquerVue_utiliseUnUpsert_fonctionneSansLigneUserConfigPreexistante() {
    // Un simple UPDATE ne ferait rien pour un utilisateur sans ligne user_config existante
    // (compte tout juste créé, aucune préférence encore enregistrée) — vérifie que la requête
    // reste bien un INSERT ... ON CONFLICT plutôt qu'une régression vers un UPDATE seul.
    service.marquerVue("u1", "2.6.4");

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("ON CONFLICT"),
            org.mockito.ArgumentMatchers.anyMap());
  }
}
