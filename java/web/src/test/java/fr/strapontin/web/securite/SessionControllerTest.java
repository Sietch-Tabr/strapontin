/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link SessionController}.
///
/// @since 2.6.6
/// @author Kwaite
class SessionControllerTest {

  private NamedParameterJdbcTemplate jdbc;
  private SessionController controller;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    controller = new SessionController(jdbc);
  }

  @Test
  void session_authNull_retourneDtoAnonyme() {
    SessionController.SessionDto dto = controller.session(null);

    assertEquals("user", dto.role());
    assertEquals(0, dto.nbNouveau());
  }

  @Test
  void session_nonAuthentifie_retourneDtoAnonyme() {
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(false);

    SessionController.SessionDto dto = controller.session(auth);

    assertEquals("user", dto.role());
    assertEquals(0, dto.nbNouveau());
  }

  @Test
  void session_principalPasUtilisateurDetails_retourneDtoAnonyme() {
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn("anonymousUser");

    SessionController.SessionDto dto = controller.session(auth);

    assertEquals("user", dto.role());
    assertEquals(0, dto.nbNouveau());
  }

  @Test
  void session_utilisateurAuthentifie_retourneRoleEtNbNouveauCalcule() {
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "admin", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(ud);
    when(jdbc.queryForObject(anyString(), eq(Map.of("userId", "user-1")), eq(Integer.class)))
        .thenReturn(5);

    SessionController.SessionDto dto = controller.session(auth);

    assertEquals("admin", dto.role());
    assertEquals(5, dto.nbNouveau());
  }

  @Test
  void session_compteNull_retourneZero() {
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    Authentication auth = mock(Authentication.class);
    when(auth.isAuthenticated()).thenReturn(true);
    when(auth.getPrincipal()).thenReturn(ud);
    when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(null);

    SessionController.SessionDto dto = controller.session(auth);

    assertEquals(0, dto.nbNouveau());
  }
}
