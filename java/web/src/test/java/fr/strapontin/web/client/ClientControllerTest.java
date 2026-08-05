/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/// Tests unitaires pour {@link ClientController} (plafonnement du nombre de clés meta).
///
/// @since 2.7.0
/// @author Kwaite
class ClientControllerTest {

  private LogAdminService logAdminService;
  private HttpServletRequest request;
  private ClientController controller;

  @BeforeEach
  void setUp() {
    logAdminService = mock(LogAdminService.class);
    request = mock(HttpServletRequest.class);
    controller = new ClientController(logAdminService);
  }

  @Test
  void signalerErreur_messageBlanc_retourne400() {
    var reponse = controller.signalerErreur(Map.of("message", "  "), request);

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void signalerErreur_messageValide_retourne201EtLogue() {
    var reponse = controller.signalerErreur(Map.of("message", "erreur JS"), request);

    assertEquals(201, reponse.getStatusCode().value());
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void signalerErreur_plusDeVingtClesMeta_neConserveQueLePlafond() {
    Map<String, Object> corps = new HashMap<>();
    corps.put("message", "erreur JS");
    for (int i = 0; i < 50; i++) {
      corps.put("cle" + i, "valeur" + i);
    }

    controller.signalerErreur(corps, request);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), captor.capture());
    assertTrue(captor.getValue().size() <= 20);
  }
}
