/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.webhooks.Webhooks;
import com.resend.services.webhooks.model.ListWebhookEventsResponseSuccess;
import com.resend.services.webhooks.model.ListWebhooksResponseSuccess;
import fr.strapontin.web.config.ConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests unitaires pour {@link ResendWebhooksAdminService}.
///
/// @since 2.9.0
/// @author Kwaite
class ResendWebhooksAdminServiceTest {

  private static final String ENDPOINT = "https://exemple.fr/api/webhooks/resend";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ConfigService configService;
  private Resend client;
  private Webhooks webhooks;
  private ResendWebhooksAdminService service;

  @BeforeEach
  void setUp() {
    configService = mock(ConfigService.class);
    client = mock(Resend.class);
    webhooks = mock(Webhooks.class);
    when(client.webhooks()).thenReturn(webhooks);
    service = new ResendWebhooksAdminService(configService, _ -> client);
  }

  private void stubConfigComplete() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.of("https://exemple.fr"));
  }

  private ListWebhooksResponseSuccess listeWebhooks(String webhookId, String endpoint)
      throws Exception {
    String json =
        """
        {"object":"list","has_more":false,"data":[
          {"id":"%s","created_at":"2026-09-01T00:00:00Z","status":"enabled","endpoint":"%s","events":["email.bounced"]}
        ]}
        """
            .formatted(webhookId, endpoint);
    return MAPPER.readValue(json, ListWebhooksResponseSuccess.class);
  }

  @Test
  void listerEvenements_apiKeyAbsente_retourneListeVide() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    assertTrue(service.listerEvenements().isEmpty());
  }

  @Test
  void listerEvenements_baseUrlAbsente_retourneListeVide() {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.of("re_test"));
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.empty());

    assertTrue(service.listerEvenements().isEmpty());
  }

  @Test
  void listerEvenements_aucunWebhookCorrespondantAUEndpoint_retourneListeVide() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenReturn(listeWebhooks("wh_1", "https://autre-domaine.fr/webhook"));

    assertTrue(service.listerEvenements().isEmpty());
  }

  @Test
  void listerEvenements_webhookTrouve_retourneLesEvenements() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenReturn(listeWebhooks("wh_1", ENDPOINT));
    String jsonEvenements =
        """
        {"object":"list","has_more":false,"data":[
          {"id":"evt_1","type":"email.bounced","created_at":"2026-09-01T00:00:00Z","status":"failed"}
        ]}
        """;
    when(webhooks.listEvents("wh_1"))
        .thenReturn(MAPPER.readValue(jsonEvenements, ListWebhookEventsResponseSuccess.class));

    var evenements = service.listerEvenements();

    assertEquals(1, evenements.size());
    assertEquals("evt_1", evenements.get(0).id());
    assertEquals("email.bounced", evenements.get(0).type());
    assertEquals("failed", evenements.get(0).statut());
  }

  @Test
  void listerEvenements_erreurApi_retourneListeVideSansPropager() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenThrow(new ResendException(500, "panne Resend"));

    assertTrue(service.listerEvenements().isEmpty());
  }

  @Test
  void rejouerEvenement_apiKeyAbsente_retourneFalse() throws Exception {
    when(configService.lireConfig("RESEND_API_KEY")).thenReturn(Optional.empty());

    assertFalse(service.rejouerEvenement("evt_1"));
    verify(webhooks, never())
        .replayEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejouerEvenement_aucunWebhookCorrespondant_retourneFalse() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenReturn(listeWebhooks("wh_1", "https://autre-domaine.fr/webhook"));

    assertFalse(service.rejouerEvenement("evt_1"));
  }

  @Test
  void rejouerEvenement_webhookTrouve_appelleReplayEventEtRetourneTrue() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenReturn(listeWebhooks("wh_1", ENDPOINT));

    boolean rejoue = service.rejouerEvenement("evt_1");

    assertTrue(rejoue);
    verify(webhooks).replayEvent("wh_1", "evt_1");
  }

  @Test
  void rejouerEvenement_erreurApi_retourneFalse() throws Exception {
    stubConfigComplete();
    when(webhooks.list()).thenReturn(listeWebhooks("wh_1", ENDPOINT));
    when(webhooks.replayEvent("wh_1", "evt_1")).thenThrow(new ResendException(500, "panne Resend"));

    assertFalse(service.rejouerEvenement("evt_1"));
  }
}
