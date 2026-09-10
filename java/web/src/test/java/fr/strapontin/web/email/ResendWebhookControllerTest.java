/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/// Tests unitaires de {@link ResendWebhookController}.
///
/// @since 2.6.3
/// @author Kwaite
class ResendWebhookControllerTest {

  private ConfigService configService;
  private EmailAlerteService emailAlerteService;
  private LogAdminService logAdminService;
  private ResendWebhookController controller;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    emailAlerteService = mock(EmailAlerteService.class);
    logAdminService = mock(LogAdminService.class);
    controller =
        new ResendWebhookController(
            configService, emailAlerteService, new ObjectMapper(), logAdminService);
  }

  @Test
  void recevoirEvenement_secretAbsent_retourne400() {
    when(configService.lireConfig("RESEND_WEBHOOK_SECRET")).thenReturn(Optional.empty());

    ResponseEntity<Void> reponse = controller.recevoirEvenement("id-1", "1700000000", "sig", "{}");

    assertEquals(HttpStatus.BAD_REQUEST, reponse.getStatusCode());
  }

  // un svix-timestamp non numérique fait lever une NumberFormatException non enveloppée
  // par le SDK resend-java (Webhooks.verify() appelle Long.parseLong sans try/catch interne).
  // Avant, seul ResendException était intercepté — cette exception technique remontait donc
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

  // l'email ne doit jamais apparaître en clair dans logs_admin, cohérent avec le hachage
  // déjà appliqué côté SLF4J juste au-dessus dans le code.
  @Test
  @SuppressWarnings("unchecked")
  void traiterEvenement_bounce_journaliseEmailHacheEtNotifieAdmin() {
    String corps =
        """
        {"type":"email.bounced","data":{"to":["victime@exemple.fr"]}}
        """;

    controller.traiterEvenement(corps, "svix-id-bounce-1");

    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), details.capture());
    assertEquals(String.valueOf("victime@exemple.fr".hashCode()), details.getValue().get("email"));
    assertEquals("svix-id-bounce-1", details.getValue().get("svixId"));
    verify(emailAlerteService).envoyerNotificationBounce("victime@exemple.fr");
  }

  // une plainte spam (email.complained) reçoit désormais le même traitement qu'un
  // bounce — logs_admin + notification admin — plutôt qu'un simple log SLF4J invisible.
  @Test
  @SuppressWarnings("unchecked")
  void traiterEvenement_complained_journaliseEtNotifieAdminCommeUnBounce() {
    String corps =
        """
        {"type":"email.complained","data":{"to":["plaignant@exemple.fr"]}}
        """;

    controller.traiterEvenement(corps, "svix-id-complained-1");

    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), details.capture());
    assertEquals(
        String.valueOf("plaignant@exemple.fr".hashCode()), details.getValue().get("email"));
    assertEquals("svix-id-complained-1", details.getValue().get("svixId"));
    verify(emailAlerteService).envoyerNotificationPlainteSpam("plaignant@exemple.fr");
    verify(emailAlerteService, never()).envoyerNotificationBounce(anyString());
  }
}
