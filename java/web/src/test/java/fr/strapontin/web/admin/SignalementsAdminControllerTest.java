/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link SignalementsAdminController}.
 *
 * @since 2.9.0
 * @author Kwaite
 */
class SignalementsAdminControllerTest {

  private SignalementsAdminService service;
  private SignalementsAdminController controller;

  @BeforeEach
  void setUp() {
    service = mock(SignalementsAdminService.class);
    controller = new SignalementsAdminController(service);
  }

  @Test
  void lister_delegueAuService() {
    when(service.lister(0)).thenReturn(new ReponseSignalements(List.of(), 0, 1, 0));

    final ResponseEntity<ReponseSignalements> reponse = controller.lister(0);

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).lister(0);
  }

  @Test
  void resoudre_signalementInconnuOuDejaResolu_retourne404() {
    when(service.resoudre(1L)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.resoudre(1L);

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void resoudre_nominal_retourne200() {
    when(service.resoudre(1L)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.resoudre(1L);

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void accepterContamination_uuidInvalide_retourne400() {
    assertEquals(400, controller.accepterContamination("pas-un-uuid").getStatusCode().value());
  }

  @Test
  void declarerSallesDistinctes_aucunSignalement_retourne404() {
    final java.util.UUID id = java.util.UUID.randomUUID();
    org.mockito.Mockito.when(service.declarerSallesDistinctes(id)).thenReturn(false);

    assertEquals(404, controller.declarerSallesDistinctes(id.toString()).getStatusCode().value());
  }
}
