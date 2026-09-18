/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.securite.UtilisateurDetails;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/**
 * Tests unitaires de {@link PushController}.
 *
 * @since 2.6.6
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class PushControllerTest {

  @Mock private PushService pushService;
  @Mock private NotificationPushService notificationPushService;

  private PushController controller;
  private Authentication auth;

  @BeforeEach
  void setUp() {
    controller = new PushController(pushService, notificationPushService);
    auth = mock(Authentication.class);
  }

  private void stubPrincipal() {
    final UtilisateurDetails ud =
        new UtilisateurDetails("user-1", "kwaite", "k@test.fr", "", "user", false, false);
    when(auth.getPrincipal()).thenReturn(ud);
  }

  @Test
  void obtenirCleVapid_delegueAuService() {
    final ReponseVapid attendu = new ReponseVapid(true, "clepublique");
    when(pushService.obtenirCleVapid()).thenReturn(attendu);

    final ResponseEntity<ReponseVapid> rep = controller.obtenirCleVapid();

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals(attendu, rep.getBody());
  }

  @Test
  void abonner_requeteValide_retourne201EtDelegueAuService() {
    stubPrincipal();
    final RequeteAbonnement requete =
        new RequeteAbonnement("https://push.exemple/abc", new ClesAbonnement("p256dh", "auth"));

    final ResponseEntity<Map<String, Object>> rep = controller.abonner(requete, auth);

    assertEquals(HttpStatus.CREATED, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("ok"));
    verify(pushService).abonner("user-1", requete);
  }

  @Test
  void abonner_capAtteint_propageErreurMetier429() {
    stubPrincipal();
    final RequeteAbonnement requete =
        new RequeteAbonnement("https://push.exemple/abc", new ClesAbonnement("p256dh", "auth"));
    doThrow(new ErreurMetier("Cap d'abonnements atteint.", 429))
        .when(pushService)
        .abonner(anyString(), any());

    final ErreurMetier e =
        org.junit.jupiter.api.Assertions.assertThrows(
            ErreurMetier.class, () -> controller.abonner(requete, auth));
    assertEquals(429, e.getStatus());
  }

  @Test
  void tester_abonnementActif_retourne200() {
    stubPrincipal();
    when(notificationPushService.envoyerNotificationTest("user-1", null)).thenReturn(true);

    final ResponseEntity<Map<String, Object>> rep = controller.tester(null, auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    assertEquals(true, rep.getBody().get("ok"));
  }

  @Test
  void tester_avecEndpointCible_transmetLendpointAuService() {
    stubPrincipal();
    when(notificationPushService.envoyerNotificationTest("user-1", "https://push.exemple/abc"))
        .thenReturn(true);

    controller.tester(new RequeteTesterPush("https://push.exemple/abc"), auth);

    verify(notificationPushService).envoyerNotificationTest("user-1", "https://push.exemple/abc");
  }

  @Test
  void tester_aucunAbonnement_retourne400() {
    stubPrincipal();
    when(notificationPushService.envoyerNotificationTest(eq("user-1"), isNull())).thenReturn(false);

    final ResponseEntity<Map<String, Object>> rep = controller.tester(null, auth);

    assertEquals(HttpStatus.BAD_REQUEST, rep.getStatusCode());
  }

  // endpoint manquant/blanc : voir RequeteDesabonnementValidationTest (@Valid ne s'exerce pas
  // sur un appel Java direct au contrôleur, seulement via Spring MVC).

  @Test
  void desabonner_endpointValide_delegueAuServiceEtRetourne200() {
    stubPrincipal();
    final ResponseEntity<Map<String, Object>> rep =
        controller.desabonner(new RequeteDesabonnement("https://push.exemple/abc"), auth);

    assertEquals(HttpStatus.OK, rep.getStatusCode());
    verify(pushService).desabonner("user-1", "https://push.exemple/abc");
  }
}
