/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires pour {@link ChangelogRestController} — badge "nouveautés" piloté par {@code
 * changelog_version_vue} comparé à la version courante lue via {@link BuildProperties}.
 *
 * @since 2.6.4
 * @author Kwaite
 */
class ChangelogRestControllerTest {

  private ChangelogService changelogService;
  private ChangelogRestController controller;

  @BeforeEach
  void setUp() {
    changelogService = mock(ChangelogService.class);
    final Properties props = new Properties();
    props.put("version", "2.6.4");
    controller = new ChangelogRestController(changelogService, new BuildProperties(props));
  }

  private static Authentication authPour(final String userId) {
    final UtilisateurDetails ud =
        new UtilisateurDetails(userId, "login", "test@test.fr", "", "user", false, false, false);
    final Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
    return auth;
  }

  @Test
  void nouveaute_versionVueDifferente_retourneNonVuVrai() {
    when(changelogService.estNonVu("u1", "2.6.4")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.nouveaute(authPour("u1"));

    assertEquals(true, reponse.getBody().get("nonVu"));
  }

  @Test
  void nouveaute_versionVueIdentique_retourneNonVuFaux() {
    when(changelogService.estNonVu("u1", "2.6.4")).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.nouveaute(authPour("u1"));

    assertEquals(false, reponse.getBody().get("nonVu"));
  }

  @Test
  void marquerVue_ecritLaVersionCouranteDynamique() {
    controller.marquerVue(authPour("u1"));

    verify(changelogService).marquerVue("u1", "2.6.4");
  }
}
