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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link ResendWebhooksAdminController}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
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
    final var evenement =
        new ResendWebhooksAdminService.EvenementWebhook(
            "evt_1", "email.bounced", "failed", "2026-09-01T00:00:00Z");
    when(service.listerEvenements()).thenReturn(List.of(evenement));

    final ResponseEntity<List<ResendWebhooksAdminService.EvenementWebhook>> reponse =
        controller.listerEvenements();

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(List.of(evenement), reponse.getBody());
  }

  @Test
  void rejouerEvenement_succes_retourne200() {
    when(service.rejouerEvenement("evt_1")).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.rejouerEvenement("evt_1");

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).rejouerEvenement("evt_1");
  }

  @Test
  void rejouerEvenement_echec_retourne502() {
    when(service.rejouerEvenement("evt_1")).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.rejouerEvenement("evt_1");

    assertEquals(502, reponse.getStatusCode().value());
  }

  @Test
  void listerTentatives_delegueAuService() {
    final var tentative =
        new ResendWebhooksAdminService.TentativeWebhook("att_1", 200, "OK", "2026-09-01T00:00:00Z");
    when(service.listerTentatives("evt_1")).thenReturn(List.of(tentative));

    final ResponseEntity<List<ResendWebhooksAdminService.TentativeWebhook>> reponse =
        controller.listerTentatives("evt_1");

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(List.of(tentative), reponse.getBody());
  }

  @Test
  void rotationSecret_succes_retourne200() {
    when(service.rotationSecretWebhook()).thenReturn(Optional.of("whsec_nouveau"));

    final ResponseEntity<Map<String, Object>> reponse = controller.rotationSecret();

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void rotationSecret_echec_retourne502() {
    when(service.rotationSecretWebhook()).thenReturn(Optional.empty());

    final ResponseEntity<Map<String, Object>> reponse = controller.rotationSecret();

    assertEquals(502, reponse.getStatusCode().value());
  }

  @Test
  void listerSuppressions_delegueAuService() {
    final var entree =
        new ResendWebhooksAdminService.EntreeSuppression(
            "sup_1", "bounced@exemple.fr", "bounce", "2026-09-01T00:00:00Z");
    when(service.listerSuppressions()).thenReturn(List.of(entree));

    final ResponseEntity<List<ResendWebhooksAdminService.EntreeSuppression>> reponse =
        controller.listerSuppressions();

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(List.of(entree), reponse.getBody());
  }
}
