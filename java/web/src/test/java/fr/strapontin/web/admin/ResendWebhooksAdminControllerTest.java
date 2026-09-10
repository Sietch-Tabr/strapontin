/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.email.ResendWebhooksAdminService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/// Tests unitaires pour {@link ResendWebhooksAdminController}.
///
/// @since 2.9.0
/// @author Kwaite
class ResendWebhooksAdminControllerTest {

  private ResendWebhooksAdminService service;
  private ResendWebhooksAdminController controller;

  @BeforeEach
  void setUp() {
    service = mock(ResendWebhooksAdminService.class);
    controller = new ResendWebhooksAdminController(service);
  }

  @Test
  void listerEvenements_delegueAuService() {
    var evenement =
        new ResendWebhooksAdminService.EvenementWebhook(
            "evt_1", "email.bounced", "failed", "2026-09-01T00:00:00Z");
    when(service.listerEvenements()).thenReturn(List.of(evenement));

    ResponseEntity<List<ResendWebhooksAdminService.EvenementWebhook>> reponse =
        controller.listerEvenements();

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(List.of(evenement), reponse.getBody());
  }

  @Test
  void rejouerEvenement_succes_retourne200() {
    when(service.rejouerEvenement("evt_1")).thenReturn(true);

    ResponseEntity<Map<String, Object>> reponse = controller.rejouerEvenement("evt_1");

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).rejouerEvenement("evt_1");
  }

  @Test
  void rejouerEvenement_echec_retourne502() {
    when(service.rejouerEvenement("evt_1")).thenReturn(false);

    ResponseEntity<Map<String, Object>> reponse = controller.rejouerEvenement("evt_1");

    assertEquals(502, reponse.getStatusCode().value());
  }
}
