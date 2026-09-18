/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires pour {@link ConcertsAdminController}.
 *
 * @since 2.8.3
 * @author Kwaite
 */
class ConcertsAdminControllerTest {

  private ConcertsAdminService service;
  private ConcertsAdminController controller;

  @BeforeEach
  void setUp() {
    service = mock(ConcertsAdminService.class);
    controller = new ConcertsAdminController(service);
  }

  @Test
  void lister_delegueAuService() {
    when(service.lister(0)).thenReturn(new ReponseConcertsAConfirmer(List.of(), 0, 1, 0));

    final ResponseEntity<ReponseConcertsAConfirmer> reponse = controller.lister(0);

    assertEquals(200, reponse.getStatusCode().value());
    verify(service).lister(0);
  }

  @Test
  void confirmer_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse = controller.confirmer("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).confirmerSuppression(any());
  }

  @Test
  void confirmer_concertInconnuOuNonFlague_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.confirmerSuppression(id)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.confirmer(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void confirmer_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.confirmerSuppression(id)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.confirmer(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }

  @Test
  void annuler_uuidInvalide_retourne400() {
    final ResponseEntity<Map<String, Object>> reponse = controller.annuler("pas-un-uuid");

    assertEquals(400, reponse.getStatusCode().value());
    verify(service, never()).annulerSuppression(any());
  }

  @Test
  void annuler_concertInconnuOuNonFlague_retourne404() {
    final UUID id = UUID.randomUUID();
    when(service.annulerSuppression(id)).thenReturn(false);

    final ResponseEntity<Map<String, Object>> reponse = controller.annuler(id.toString());

    assertEquals(404, reponse.getStatusCode().value());
  }

  @Test
  void annuler_nominal_retourne200() {
    final UUID id = UUID.randomUUID();
    when(service.annulerSuppression(id)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> reponse = controller.annuler(id.toString());

    assertEquals(200, reponse.getStatusCode().value());
  }
}
