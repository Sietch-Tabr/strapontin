/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/// Tests unitaires de {@link SpectacleController}.
///
/// @since 2.6.6
/// @author Kwaite
@ExtendWith(MockitoExtension.class)
class SpectacleControllerTest {

  @Mock private SpectacleService spectacleService;

  private SpectacleController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new SpectacleController(spectacleService);
    auth = mock(Authentication.class);
  }

  private void stubPrincipal() {
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void listerSpectacles_delegueAuServiceAvecUserId() {
    stubPrincipal();
    ReponseSpectacles attendu =
        new ReponseSpectacles(List.of(), 0, 1, 0, List.of(), List.of(), List.of(), true, false);
    when(spectacleService.listerSpectacles(eq("user-1"), any(FiltreConcerts.class)))
        .thenReturn(attendu);

    ResponseEntity<ReponseSpectacles> rep =
        controller.listerSpectacles(
            "q", "2026-05", "concert", "Pop", false, "date", false, null, null, 0, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertSame(attendu, rep.getBody());
  }

  @Test
  void modifierMatch_delegueAuService() {
    stubPrincipal();
    controller.modifierMatch("concert-1", new RequeteMasque(true), auth);

    verify(spectacleService).modifierMatch("user-1", "concert-1", true);
  }

  @Test
  void masquerArtiste_artisteIdManquant_retourne400SansDelegation() {
    ResponseEntity<?> rep = controller.masquerArtiste(new RequeteMasquerArtiste(null), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(spectacleService, never()).masquerArtiste(any(), any());
  }

  @Test
  void masquerArtiste_artisteIdInvalide_retourne400SansDelegation() {
    ResponseEntity<?> rep =
        controller.masquerArtiste(new RequeteMasquerArtiste("pas-un-uuid"), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(spectacleService, never()).masquerArtiste(any(), any());
  }

  @Test
  void masquerArtiste_artisteIdValide_delegueAuService() {
    stubPrincipal();
    UUID artisteId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    ResponseEntity<?> rep =
        controller.masquerArtiste(new RequeteMasquerArtiste(artisteId.toString()), auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(spectacleService).masquerArtiste("user-1", artisteId);
  }

  @Test
  void demasquerArtiste_artisteIdManquant_retourne400SansDelegation() {
    ResponseEntity<?> rep = controller.demasquerArtiste(new RequeteMasquerArtiste(null), auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
    verify(spectacleService, never()).demasquerArtiste(any(), any());
  }

  @Test
  void demasquerArtiste_artisteIdValide_delegueAuService() {
    stubPrincipal();
    UUID artisteId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    ResponseEntity<?> rep =
        controller.demasquerArtiste(new RequeteMasquerArtiste(artisteId.toString()), auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(spectacleService).demasquerArtiste("user-1", artisteId);
  }
}
