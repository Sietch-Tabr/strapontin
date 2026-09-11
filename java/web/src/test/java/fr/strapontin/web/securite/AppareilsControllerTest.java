/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.securite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires pour {@link AppareilsController}.
 *
 * @since 2.7.8
 * @author Kwaite
 */
class AppareilsControllerTest {

  private AppareilsService appareilsService;
  private AppareilsController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    appareilsService = mock(AppareilsService.class);
    controller = new AppareilsController(appareilsService);
    UtilisateurDetails ud =
        new UtilisateurDetails("u1", "login", "test@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void lister_delegueAuServiceAvecLaSessionCourante() {
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    HttpSession session = mock(HttpSession.class);
    when(session.getId()).thenReturn("sess-1");
    when(httpRequest.getSession(false)).thenReturn(session);
    List<AppareilsService.AppareilDto> attendu =
        List.of(
            new AppareilsService.AppareilDto(
                "id-1", "Mozilla/5.0", Instant.now(), Instant.now(), true));
    when(appareilsService.listerAppareils("u1", "sess-1")).thenReturn(attendu);

    var resultat = controller.lister(auth, httpRequest);

    assertEquals(attendu, resultat);
  }

  @Test
  void revoquer_appareilExistant_supprime() {
    when(appareilsService.revoquerAppareil("u1", "appareil-1")).thenReturn(true);

    var reponse = controller.revoquer("appareil-1", auth);

    assertEquals(204, reponse.getStatusCode().value());
    verify(appareilsService).revoquerAppareil("u1", "appareil-1");
  }

  @Test
  void revoquer_appareilInexistant_leveErreur404() {
    when(appareilsService.revoquerAppareil("u1", "inconnu")).thenReturn(false);

    ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> controller.revoquer("inconnu", auth));

    assertEquals(404, erreur.getStatus());
  }
}
