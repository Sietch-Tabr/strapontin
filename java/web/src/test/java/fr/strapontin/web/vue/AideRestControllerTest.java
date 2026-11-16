/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.vue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import fr.strapontin.sources.http.IdentiteHttp;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailService;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link AideRestController}.
 *
 * <p>Couvre le rate limiting par utilisateur (3 signalements/h) et la validation du message.
 *
 * @since 2.2.0
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class AideRestControllerTest {

  private static final IdentiteHttp IDENTITE =
      new IdentiteHttp("test", "https://exemple.test/projet", () -> "");

  @Mock private EmailService emailService;
  @Mock private ConfigService configService;
  @Mock private Authentication auth;

  private AideRestController controller;

  @BeforeEach
  void setUp() {
    controller = new AideRestController(emailService, configService, IDENTITE);
    final UtilisateurDetails utilisateur =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false, false);
    lenient().when(auth.getPrincipal()).thenReturn(utilisateur);
  }

  @Test
  void config_retourneResendDisponibleContactEmailEtSignalLien() {
    when(emailService.estDisponible()).thenReturn(true);
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("contact@test.fr"));
    when(configService.lireConfig("SIGNAL_LIEN")).thenReturn(Optional.of("https://signal.me/x"));

    final var rep = controller.config();

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("resendDisponible"));
    assertEquals("contact@test.fr", rep.getBody().get("contactEmail"));
    assertEquals("https://signal.me/x", rep.getBody().get("signalLien"));
    assertEquals("https://exemple.test/projet", rep.getBody().get("urlProjet"));
  }

  @Test
  void config_valeursAbsentes_replieSurChainesVides() {
    when(emailService.estDisponible()).thenReturn(false);
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.empty());
    when(configService.lireConfig("SIGNAL_LIEN")).thenReturn(Optional.empty());

    final var rep = controller.config();

    assertEquals(false, rep.getBody().get("resendDisponible"));
    assertEquals("", rep.getBody().get("contactEmail"));
    assertEquals("", rep.getBody().get("signalLien"));
  }

  @Test
  void signaler_troisSignalementsSuccessifs_sontAcceptes() {
    for (int i = 0; i < 3; i++) {
      final var rep = controller.signaler(new RequeteSignalement("bug", "message test " + i), auth);
      assertEquals(
          HttpStatus.NO_CONTENT, rep.getStatusCode(), "Le signalement " + (i + 1) + " doit passer");
    }
  }

  @Test
  void signaler_quatriemeSignalement_retourneErreurRateLimit() {
    for (int i = 0; i < 3; i++) {
      controller.signaler(new RequeteSignalement("bug", "message test"), auth);
    }
    final var rep = controller.signaler(new RequeteSignalement("bug", "message test"), auth);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  @Test
  void signaler_utilisateursDifferents_quotasIndependants() {
    final var autre = authDe("user-2", "autre@test.fr");
    for (int i = 0; i < 3; i++) {
      controller.signaler(new RequeteSignalement("bug", "msg"), auth);
    }
    final var rep = controller.signaler(new RequeteSignalement("bug", "msg"), autre);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
  }

  // message vide/trop long : voir RequeteSignalementValidationTest (@Valid ne s'exerce pas sur
  // un appel Java direct au contrôleur, seulement via Spring MVC).

  @Test
  void signaler_memeUtilisateurDerriereAdressesDifferentes_quotaPartage() {
    for (int i = 0; i < 3; i++) {
      controller.signaler(new RequeteSignalement("bug", "msg"), auth);
    }
    final var rep = controller.signaler(new RequeteSignalement("bug", "msg"), auth);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  @Test
  void signaler_typeValide_estTransmisTelQuelAEmailService() {
    controller.signaler(new RequeteSignalement("suggestion", "message"), auth);

    verify(emailService).envoyerSignalement("suggestion", "message", "k@test.fr");
  }

  @Test
  void signaler_typeInvalide_replieSurQuestion() {
    controller.signaler(new RequeteSignalement("pas-un-type", "message"), auth);

    verify(emailService).envoyerSignalement("question", "message", "k@test.fr");
  }

  @Test
  void signaler_principalPasUtilisateurDetails_retourne401SansEnvoi() {
    final Authentication authAnonyme = mock(Authentication.class);
    when(authAnonyme.getPrincipal()).thenReturn("anonymousUser");

    final var rep = controller.signaler(new RequeteSignalement("bug", "message"), authAnonyme);

    assertEquals(HttpStatus.UNAUTHORIZED, rep.getStatusCode());
    verify(emailService, never()).envoyerSignalement(anyString(), anyString(), anyString());
  }

  private static Authentication authDe(final String id, final String email) {
    final Authentication autre = mock(Authentication.class);
    when(autre.getPrincipal())
        .thenReturn(new UtilisateurDetails(id, id, email, "", "user", false, false, false));
    return autre;
  }
}
