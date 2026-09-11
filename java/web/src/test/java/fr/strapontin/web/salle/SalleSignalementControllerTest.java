/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

/**
 * Tests unitaires de l'endpoint POST /api/salles/signaler de {@link SalleController}.
 *
 * <p>Couvre le rate limiting (3/h par IP), la validation des champs, et la délégation email.
 *
 * @since 2.2.5
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class SalleSignalementControllerTest {

  @Mock private SalleService salleService;
  @Mock private EmailService emailService;
  @Mock private Authentication auth;

  private SalleController controller;

  @BeforeEach
  void setUp() {
    controller = new SalleController(salleService, emailService);
    final UtilisateurDetails utilisateur =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(utilisateur);
  }

  @Test
  void signalerManquante_nomValide_retourne204() {
    final var rep =
        controller.signalerManquante(
            new RequeteSignalementSalle("La Cigale", "Paris 75018", "", ""),
            auth,
            requete("10.1.0.1"));
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(emailService)
        .envoyerSignalementSalleManquante("La Cigale", "Paris 75018", "", "", "k@test.fr");
  }

  @Test
  void signalerManquante_troisSignalementsSuccessifs_sontAcceptes() {
    final var request = requete("10.1.0.2");
    for (int i = 0; i < 3; i++) {
      final var rep =
          controller.signalerManquante(
              new RequeteSignalementSalle("Salle " + i, "", "", ""), auth, request);
      assertEquals(
          HttpStatus.NO_CONTENT, rep.getStatusCode(), "Le signalement " + (i + 1) + " doit passer");
    }
  }

  @Test
  void signalerManquante_quatriemeSignalement_retourneRateLimit() {
    final var request = requete("10.1.0.3");
    for (int i = 0; i < 3; i++) {
      controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth, request);
    }
    final var rep =
        controller.signalerManquante(
            new RequeteSignalementSalle("Salle", "", "", ""), auth, request);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  @Test
  void signalerManquante_ipsDifferentes_bucketsIndependants() {
    final var ip1 = requete("10.1.0.4");
    final var ip2 = requete("10.1.0.5");
    for (int i = 0; i < 3; i++) {
      controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth, ip1);
    }
    final var rep =
        controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth, ip2);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
  }

  // Validation nom/longueurs (blanc, null, trop long) : voir RequeteSignalementSalleValidationTest
  // — @Valid s'exerce désormais avant même l'exécution de cette méthode (donc avant le
  // rate-limiting ci-dessus, garantie plus forte que l'ancien contrôle manuel), mais ne s'exerce
  // jamais sur un appel Java direct au contrôleur, seulement via Spring MVC.

  @Test
  void signalerManquante_champsFacultatifsBlancs_passeEtStrip() {
    final var rep =
        controller.signalerManquante(
            new RequeteSignalementSalle("Salle", "  ", "  ", "  "), auth, requete("10.1.0.10"));
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(emailService).envoyerSignalementSalleManquante("Salle", "", "", "", "k@test.fr");
  }

  private static MockHttpServletRequest requete(final String ip) {
    final var request = new MockHttpServletRequest();
    request.setRemoteAddr(ip);
    return request;
  }
}
