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
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false, false);
    when(auth.getPrincipal()).thenReturn(utilisateur);
  }

  @Test
  void signalerManquante_nomValide_retourne204() {
    final var rep =
        controller.signalerManquante(
            new RequeteSignalementSalle("La Cigale", "Paris 75018", "", ""), auth);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(emailService)
        .envoyerSignalementSalleManquante(
            new EmailService.SignalementSalleManquante(
                "La Cigale", "Paris 75018", "", "", "k@test.fr"));
  }

  @Test
  void signalerManquante_troisSignalementsSuccessifs_sontAcceptes() {
    for (int i = 0; i < 3; i++) {
      final var rep =
          controller.signalerManquante(new RequeteSignalementSalle("Salle " + i, "", "", ""), auth);
      assertEquals(
          HttpStatus.NO_CONTENT, rep.getStatusCode(), "Le signalement " + (i + 1) + " doit passer");
    }
  }

  @Test
  void signalerManquante_quatriemeSignalement_retourneRateLimit() {
    for (int i = 0; i < 3; i++) {
      controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth);
    }
    final var rep =
        controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth);
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, rep.getStatusCode());
  }

  @Test
  void signalerManquante_utilisateursDifferents_quotasIndependants() {
    final Authentication autre = mock(Authentication.class);
    when(autre.getPrincipal())
        .thenReturn(
            new UtilisateurDetails(
                "user-2", "autre", "autre@test.fr", "", "user", false, false, false));
    for (int i = 0; i < 3; i++) {
      controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), auth);
    }
    final var rep =
        controller.signalerManquante(new RequeteSignalementSalle("Salle", "", "", ""), autre);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
  }

  // Validation nom/longueurs (blanc, null, trop long) : voir RequeteSignalementSalleValidationTest
  // — @Valid s'exerce désormais avant même l'exécution de cette méthode (donc avant le
  // rate-limiting ci-dessus, garantie plus forte que l'ancien contrôle manuel), mais ne s'exerce
  // jamais sur un appel Java direct au contrôleur, seulement via Spring MVC.

  @Test
  void signalerManquante_champsFacultatifsBlancs_passeEtStrip() {
    final var rep =
        controller.signalerManquante(new RequeteSignalementSalle("Salle", "  ", "  ", "  "), auth);
    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(emailService)
        .envoyerSignalementSalleManquante(
            new EmailService.SignalementSalleManquante("Salle", "", "", "", "k@test.fr"));
  }
}
