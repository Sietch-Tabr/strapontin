/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.utilisateur.ParametresService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link SessionController}.
///
/// <p>Depuis, le COUNT SQL est délégué à {@link ParametresService} (extrait vers
/// {@code UtilisateurRepository}) — ce contrôleur redevient HTTP pur, conforme au pattern
/// Controller → Service → Repository.
///
/// @since 2.6.6
/// @author Kwaite
class SessionControllerTest {

  private ParametresService parametresService;
  private SessionController controller;

  @BeforeEach
  void setUp() {
    parametresService = mock(ParametresService.class);
    controller = new SessionController(parametresService);
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
    when(parametresService.compterNouveauxMatchesArtistes("user-1")).thenReturn(5);

    SessionController.SessionDto dto = controller.session(auth);

    assertEquals("admin", dto.role());
    assertEquals(5, dto.nbNouveau());
  }
}
