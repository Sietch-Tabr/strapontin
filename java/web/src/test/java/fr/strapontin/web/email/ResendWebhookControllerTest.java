/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/// Tests unitaires de {@link ResendWebhookController} (#810).
///
/// @since 2.6.3
/// @author Kwaite
class ResendWebhookControllerTest {

  private ConfigService configService;
  private ResendWebhookController controller;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    controller =
        new ResendWebhookController(
            configService,
            mock(EmailService.class),
            new ObjectMapper(),
            mock(LogAdminService.class));
  }

  @Test
  void recevoirEvenement_secretAbsent_retourne400() {
    when(configService.lireConfig("RESEND_WEBHOOK_SECRET")).thenReturn(Optional.empty());

    ResponseEntity<Void> reponse = controller.recevoirEvenement("id-1", "1700000000", "sig", "{}");

    assertEquals(HttpStatus.BAD_REQUEST, reponse.getStatusCode());
  }

  // #810 : un svix-timestamp non numérique fait lever une NumberFormatException non enveloppée
  // par le SDK resend-java (Webhooks.verify() appelle Long.parseLong sans try/catch interne).
  // Avant #810, seul ResendException était intercepté — cette exception technique remontait donc
  // en 500 (via GlobalExceptionHandler) plutôt qu'en 401, avec un warn logs_admin parasite.
  @Test
  void recevoirEvenement_svixTimestampNonNumerique_retourne401PasUne500() {
    when(configService.lireConfig("RESEND_WEBHOOK_SECRET"))
        .thenReturn(Optional.of("whsec_test1234567890"));

    ResponseEntity<Void> reponse =
        controller.recevoirEvenement("id-1", "abc-non-numerique", "v1,signature", "{}");

    assertEquals(HttpStatus.UNAUTHORIZED, reponse.getStatusCode());
  }

  @Test
  void recevoirEvenement_signatureInvalide_retourne401() {
    when(configService.lireConfig("RESEND_WEBHOOK_SECRET"))
        .thenReturn(Optional.of("whsec_test1234567890"));

    ResponseEntity<Void> reponse =
        controller.recevoirEvenement("id-1", "1700000000", "v1,signature-invalide", "{}");

    assertEquals(HttpStatus.UNAUTHORIZED, reponse.getStatusCode());
  }
}
