/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/// Tests unitaires de {@link AideViewController}.
///
/// <p>Couvre le rate limiting par IP (3 signalements/h) et la validation du message.
///
/// @since 2.0.5
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class AideViewControllerTest {

  @Mock private EmailService emailService;
  @Mock private ConfigService configService;
  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private Authentication auth;

  private AideViewController controller;

  @BeforeEach
  void setUp() {
    controller = new AideViewController(emailService, configService, jdbc);
    UtilisateurDetails utilisateur =
        new UtilisateurDetails("user-1", "kwaite", "Kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(utilisateur);
  }

  @Test
  void signaler_troisSignalementsSuccessifs_sontAcceptes() {
    var request = requete("10.0.0.1");
    for (int i = 0; i < 3; i++) {
      var redirects = new RedirectAttributesModelMap();
      controller.signaler("bug", "message test " + i, redirects, auth, request);
      assertFalse(
          redirects.getFlashAttributes().containsKey("erreurSignalement"),
          "Le signalement " + (i + 1) + " doit passer");
    }
  }

  @Test
  void signaler_quatriemeSignalement_retourneErreurRateLimit() {
    var request = requete("10.0.0.2");
    for (int i = 0; i < 3; i++) {
      controller.signaler("bug", "message test", new RedirectAttributesModelMap(), auth, request);
    }
    var redirects = new RedirectAttributesModelMap();
    controller.signaler("bug", "message test", redirects, auth, request);
    assertTrue(redirects.getFlashAttributes().containsKey("erreurSignalement"));
  }

  @Test
  void signaler_ipsDifferentes_bucketsIndependants() {
    var ip1 = requete("10.0.0.3");
    var ip2 = requete("10.0.0.4");
    for (int i = 0; i < 3; i++) {
      controller.signaler("bug", "msg", new RedirectAttributesModelMap(), auth, ip1);
    }
    var redirects = new RedirectAttributesModelMap();
    controller.signaler("bug", "msg", redirects, auth, ip2);
    assertFalse(redirects.getFlashAttributes().containsKey("erreurSignalement"));
  }

  @Test
  void signaler_messageVide_retourneErreur() {
    var redirects = new RedirectAttributesModelMap();
    controller.signaler("bug", "   ", redirects, auth, requete("10.0.0.5"));
    assertTrue(redirects.getFlashAttributes().containsKey("erreurSignalement"));
  }

  @Test
  void signaler_messageTropLong_retourneErreur() {
    var redirects = new RedirectAttributesModelMap();
    controller.signaler("bug", "a".repeat(2001), redirects, auth, requete("10.0.0.6"));
    assertTrue(redirects.getFlashAttributes().containsKey("erreurSignalement"));
  }

  private static MockHttpServletRequest requete(String ip) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(ip);
    return request;
  }
}
