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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.strapontin.web.admin.LogAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests unitaires pour {@link ClientController} (plafonnement des clés meta, limitation de
 * débit et dédoublonnage par utilisateur).
 *
 * @since 2.7.0
 * @author Kwaite
 */
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
    final var reponse = controller.signalerErreur(Map.of("message", "  "), null, request);

    assertEquals(400, reponse.getStatusCode().value());
  }

  @Test
  void signalerErreur_messageValide_retourne201EtLogue() {
    final var reponse = controller.signalerErreur(Map.of("message", "erreur JS"), null, request);

    assertEquals(201, reponse.getStatusCode().value());
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void signalerErreur_plusDeVingtClesMeta_neConserveQueLePlafond() {
    final Map<String, Object> corps = new HashMap<>();
    corps.put("message", "erreur JS");
    for (int i = 0; i < 50; i++) {
      corps.put("cle" + i, "valeur" + i);
    }

    controller.signalerErreur(corps, null, request);

    final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), captor.capture());
    assertTrue(captor.getValue().size() <= 20);
  }

  // une clé meta arbitraire de plusieurs Mo permettait sinon de faire grossir sans borne une
  // ligne logs_admin (jsonb), contrairement à message/url/userAgent déjà tronqués.
  @Test
  @SuppressWarnings("unchecked")
  void signalerErreur_cleMetaTropLongue_estTronquee() {
    final Map<String, Object> corps = new HashMap<>();
    corps.put("message", "erreur JS");
    corps.put("cleArbitraire", "x".repeat(10_000));

    controller.signalerErreur(corps, null, request);

    final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(logAdminService).logAdmin(anyString(), anyString(), anyString(), captor.capture());
    assertEquals(500, ((String) captor.getValue().get("cleArbitraire")).length());
  }

  @Test
  void signalerErreur_memeMessageRepete_nEstJournaliseQuUneFois() {
    for (int i = 0; i < 5; i++) {
      assertEquals(
          201,
          controller
              .signalerErreur(Map.of("message", "boucle"), null, request)
              .getStatusCode()
              .value());
    }

    verify(logAdminService, times(1)).logAdmin(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void signalerErreur_auDelaDuPlafondHoraire_retourne429SansJournaliser() {
    for (int i = 0; i < ClientController.MAX_ERREURS_PAR_HEURE; i++) {
      controller.signalerErreur(Map.of("message", "erreur " + i), null, request);
    }

    final var reponse = controller.signalerErreur(Map.of("message", "une de trop"), null, request);

    assertEquals(429, reponse.getStatusCode().value());
    verify(logAdminService, times(ClientController.MAX_ERREURS_PAR_HEURE))
        .logAdmin(anyString(), anyString(), anyString(), anyMap());
  }
}
