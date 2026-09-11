/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * Tests unitaires de {@link AccueilController}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class AccueilControllerTest {

  @Mock private AccueilService accueilService;

  private AccueilController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new AccueilController(accueilService);
    UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void marquerVus_delegueAuServiceEtRetourne204() {
    ResponseEntity<Void> rep = controller.marquerVus(auth);

    assertEquals(HttpStatus.NO_CONTENT, rep.getStatusCode());
    verify(accueilService).marquerVus("user-1");
  }

  @Test
  void accueil_masquesEtIndisponiblesEtNouveau_transmisAuService() {
    ReponseAccueil attendu = new ReponseAccueil(List.of(), 0, 0);
    when(accueilService.accueil("user-1", true, "1", true)).thenReturn(attendu);

    ResponseEntity<ReponseAccueil> rep = controller.accueil(true, "1", true, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertSame(attendu, rep.getBody());
  }

  @Test
  void accueil_valeursParDefaut_transmisesAuService() {
    ReponseAccueil attendu = new ReponseAccueil(List.of(), 0, 0);
    when(accueilService.accueil("user-1", false, null, false)).thenReturn(attendu);

    controller.accueil(false, null, false, auth);

    verify(accueilService).accueil("user-1", false, null, false);
  }
}
