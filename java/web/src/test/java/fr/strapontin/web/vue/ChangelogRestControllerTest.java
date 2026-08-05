/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;

/// Tests unitaires pour {@link ChangelogRestController} — badge "nouveautés" piloté par
/// {@code changelog_version_vue} comparé à la version courante lue via {@link BuildProperties}.
///
/// @since 2.6.4
/// @author Kwaite
class ChangelogRestControllerTest {

  private NamedParameterJdbcTemplate jdbc;
  private ChangelogRestController controller;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    Properties props = new Properties();
    props.put("version", "2.6.4");
    controller = new ChangelogRestController(jdbc, new BuildProperties(props));
  }

  private static Authentication authPour(String userId) {
    UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void nouveaute_versionVueDifferente_retourneNonVuVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("2.6.2"));

    ResponseEntity<Map<String, Object>> reponse = controller.nouveaute(authPour("u1"));

    assertEquals(true, reponse.getBody().get("nonVu"));
  }

  @Test
  void nouveaute_versionVueIdentique_retourneNonVuFaux() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("2.6.4"));

    ResponseEntity<Map<String, Object>> reponse = controller.nouveaute(authPour("u1"));

    assertEquals(false, reponse.getBody().get("nonVu"));
  }

  @Test
  void nouveaute_jamaisVue_retourneNonVuVrai() {
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

    ResponseEntity<Map<String, Object>> reponse = controller.nouveaute(authPour("u1"));

    assertEquals(true, reponse.getBody().get("nonVu"));
  }

  @Test
  void marquerVue_ecritLaVersionCouranteDynamique() {
    controller.marquerVue(authPour("u1"));

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, ?> params) -> "2.6.4".equals(params.get("v"))));
  }
}
