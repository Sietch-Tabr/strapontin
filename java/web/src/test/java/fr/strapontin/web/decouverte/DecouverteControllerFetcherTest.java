/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Tests unitaires de {@link DecouverteController#fetcher} — rate-limite le
 * déclenchement du fetch global, accessible à tout utilisateur authentifié sans garde-fou
 * auparavant.
 *
 * @since 2.6.3
 * @author Kwaite
 */
class DecouverteControllerFetcherTest {

  private DecouverteService decouverteService;
  private DecouverteController controller;

  @BeforeEach
  void setUp() {
    decouverteService = mock(DecouverteService.class);
    controller = new DecouverteController(decouverteService);
    when(decouverteService.fetcher()).thenReturn(ResponseEntity.ok(Map.of("statut", "déclenché")));
  }

  @Test
  void fetcher_premierAppel_declencheEtRetourne200() {
    ResponseEntity<Map<String, Object>> reponse = controller.fetcher(new RequeteFetch(List.of()));

    assertEquals(200, reponse.getStatusCode().value());
    verify(decouverteService, times(1)).fetcher();
  }

  @Test
  void fetcher_appelsRepetes_retourne429SansRedeclencher() {
    controller.fetcher(new RequeteFetch(List.of()));

    ResponseEntity<Map<String, Object>> deuxieme = controller.fetcher(new RequeteFetch(List.of()));

    assertEquals(429, deuxieme.getStatusCode().value());
    verify(decouverteService, times(1)).fetcher();
  }
}
