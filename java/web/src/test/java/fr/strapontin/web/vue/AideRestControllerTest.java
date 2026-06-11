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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link AideRestController}.
///
/// <p>Couvre le rate limiting par IP (3 signalements/h) et la validation du message.
///
/// @since 2.2.0
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class AideRestControllerTest {

  @Mock private EmailService emailService;
  @Mock private ConfigService configService;
  @Mock private Authentication auth;

  private AideRestController controller;

  @BeforeEach
  void setUp() {
    controller = new AideRestController(emailService, configService);
    UtilisateurDetails utilisateur =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(utilisateur);
  }

  @Test
  void signaler_troisSignalementsSuccessifs_sontAcceptes() {
    var request = requete("10.0.0.1");
    for (int i = 0; i < 3; i++) {
      var rep =
          controller.signaler(new RequeteSignalement("bug", "message test " + i), auth, request);
      assertEquals(
          HttpStatus.NO_CONTENT, rep.getStatusCode(), "Le signalement " + (i + 1) + " doit passer");
    }
  }

  @Test
  void signaler_quatriemeSignalement_retourneErreurRateLimit() {
    var request = requete("10.0.0.2");
    for (int i = 0; i < 3; i++) {
      controller.signaler(new RequeteSignalement("bug", "message test"), auth, request);
    }
    var rep = controller.signaler(new RequeteSignalement("bug", "message test"), auth, request);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  @Test
  void signaler_ipsDifferentes_bucketsIndependants() {
    var ip1 = requete("10.0.0.3");
    var ip2 = requete("10.0.0.4");
    for (int i = 0; i < 3; i++) {
      controller.signaler(new RequeteSignalement("bug", "msg"), auth, ip1);
    }
    var rep = controller.signaler(new RequeteSignalement("bug", "msg"), auth, ip2);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
  }

  @Test
  void signaler_messageVide_retourneErreur() {
    var rep = controller.signaler(new RequeteSignalement("bug", "   "), auth, requete("10.0.0.5"));
    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
  }

  @Test
  void signaler_messageTropLong_retourneErreur() {
    var rep =
        controller.signaler(
            new RequeteSignalement("bug", "a".repeat(2001)), auth, requete("10.0.0.6"));
    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
  }

  @Test
  void signaler_headerXRealIpIgnore_seulRemoteAddrCompte() {
    // Épuise le bucket de 10.0.0.20 avec X-Real-IP variés (tous ignorés)
    for (int i = 0; i < 3; i++) {
      var req = new MockHttpServletRequest();
      req.setRemoteAddr("10.0.0.20");
      req.addHeader("X-Real-IP", "1.2.3." + i);
      controller.signaler(new RequeteSignalement("bug", "msg"), auth, req);
    }
    // Même remoteAddr → bucket épuisé même avec un X-Real-IP différent
    var req429 = new MockHttpServletRequest();
    req429.setRemoteAddr("10.0.0.20");
    req429.addHeader("X-Real-IP", "9.9.9.9");
    var rep = controller.signaler(new RequeteSignalement("bug", "msg"), auth, req429);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  private static MockHttpServletRequest requete(String ip) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(ip);
    return request;
  }
}
