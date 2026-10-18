/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires de {@link SourcesController}.
 *
 * @since 2.7.11
 * @author Kwaite
 */
class SourcesControllerTest {

  private SourcesService sourcesService;
  private WorkerProxyService workerProxyService;
  private SourcesController controller;

  @BeforeEach
  void setUp() {
    sourcesService = mock(SourcesService.class);
    workerProxyService = mock(WorkerProxyService.class);
    controller = new SourcesController(sourcesService, workerProxyService);
  }

  @Test
  void mettreAJourLieuEventim_uuidInvalide_retourne400() {
    final ResponseEntity<?> reponse =
        controller.mettreAJourLieuEventim("pas-un-uuid", new RequeteLieuEventim("Accor Arena"));

    assertEquals(400, reponse.getStatusCode().value());
    verify(sourcesService, org.mockito.Mockito.never()).mettreAJourLieuEventim(any(), any());
  }

  @Test
  void mettreAJourLieuEventim_nominal_delegueAuServiceEtRetourne200() {
    final UUID id = UUID.randomUUID();

    final ResponseEntity<?> reponse =
        controller.mettreAJourLieuEventim(id.toString(), new RequeteLieuEventim("Accor Arena"));

    assertEquals(200, reponse.getStatusCode().value());
    verify(sourcesService).mettreAJourLieuEventim(id, "Accor Arena");
  }

  @Test
  void testerLieuEventim_accessible_retourne200AvecNbConcerts() {
    when(sourcesService.testerLieuEventim("Accor Arena")).thenReturn(5);

    final ResponseEntity<?> reponse =
        controller.testerLieuEventim("id-non-utilise", new RequeteLieuEventim("Accor Arena"));

    assertEquals(200, reponse.getStatusCode().value());
    assertEquals(java.util.Map.of("accessible", true, "nbConcerts", 5), reponse.getBody());
  }

  @Test
  void testerLieuEventim_inaccessible_retourne404() {
    when(sourcesService.testerLieuEventim("Inconnu")).thenReturn(-1);

    final ResponseEntity<?> reponse =
        controller.testerLieuEventim("id-non-utilise", new RequeteLieuEventim("Inconnu"));

    assertEquals(404, reponse.getStatusCode().value());
  }
}
