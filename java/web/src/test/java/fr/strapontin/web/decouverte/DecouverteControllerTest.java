/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link DecouverteController#decouvrir}.
 *
 * <p>Les tests de {@code fetcher()} vivent dans {@link DecouverteControllerFetcherTest}.
 * Les tests du filtre mois et du comportement global de {@code decouvrir} au niveau service
 * vivent dans {@link DecouverteServiceTest}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class DecouverteControllerTest {

  @Mock private DecouverteService decouverteService;

  private DecouverteController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new DecouverteController(decouverteService);
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void decouvrir_construitLeFiltreEtDelegueAuServiceAvecUserId() {
    ReponseDecouverte attendu =
        new ReponseDecouverte(
            List.of(), 0, 1, 0, List.of(), List.of(), List.of(), List.of(), null, List.of());
    when(decouverteService.decouvrir(eq("user-1"), any(FiltreConcerts.class))).thenReturn(attendu);

    ResponseEntity<ReponseDecouverte> rep =
        controller.decouvrir(
            List.of("75", "92"), "zenith", "2026-05", "Pop", "concert", "nouveaute", 1, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertSame(attendu, rep.getBody());
  }

  @Test
  void decouvrir_transmetLesCriteresDansLeFiltre() {
    ReponseDecouverte vide =
        new ReponseDecouverte(
            List.of(), 0, 1, 0, List.of(), List.of(), List.of(), List.of(), null, List.of());
    when(decouverteService.decouvrir(eq("user-1"), any(FiltreConcerts.class))).thenReturn(vide);

    controller.decouvrir(List.of("75"), "q", "2026-05", "Pop", "concert", "nouveaute", 2, auth);

    org.mockito.ArgumentCaptor<FiltreConcerts> captor =
        org.mockito.ArgumentCaptor.forClass(FiltreConcerts.class);
    org.mockito.Mockito.verify(decouverteService).decouvrir(eq("user-1"), captor.capture());
    FiltreConcerts filtre = captor.getValue();
    assertEquals("q", filtre.q());
    assertEquals("2026-05", filtre.mois());
    assertEquals("Pop", filtre.genre());
    assertEquals("concert", filtre.classification());
    assertEquals("nouveaute", filtre.tri());
    assertEquals(2, filtre.page());
    assertEquals(List.of("75"), filtre.depts());
  }
}
