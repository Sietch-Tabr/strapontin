/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ClientWebPush;
import fr.strapontin.core.FournisseurBouncyCastle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Tests unitaires de {@link NotificationVersionPushService} (#1231).
 *
 * @since 3.0.0
 * @author Kwaite
 */
@ExtendWith(MockitoExtension.class)
class NotificationVersionPushServiceTest {

  static {
    // Voir NotificationDigestServiceTest : BouncyCastle normalement enregistré par
    // WorkerApplication#main, requis ici pour parser réellement p256dh.
    FournisseurBouncyCastle.garantir();
  }

  @Mock private ConfigServiceWorker configService;
  @Mock private NamedParameterJdbcTemplate jdbc;
  @Mock private LogsAdminWorker logsAdminWorker;

  private static Map<String, Object> abonnementTest() {
    final Map<String, Object> ab = new HashMap<>();
    ab.put("id", 1);
    ab.put("user_id", "u1");
    ab.put("endpoint", "https://fcm.googleapis.com/fcm/send/abonnement-xyz");
    ab.put(
        "p256dh",
        "BPKi4XnOYaPYQWcKZsUfMxTQZV0JyJA-zx65BGYqeHJ8KDlO2AL5lhoFUCXC1iFMUlTzt_sgos9GiYMxFEgCDQE");
    ab.put("auth", "RZIK8q0lRcIbLO9VyII_4Q");
    return ab;
  }

  @Test
  void notifierVersion_vapidAbsent_retourneZeroSansChargerAbonnements() {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.empty());
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.empty());
    final NotificationVersionPushService service =
        new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> mock(ClientWebPush.class));

    final int nbEnvoyes = service.notifierVersion("3.0.0");

    assertEquals(0, nbEnvoyes);
    verify(jdbc, never()).queryForList(anyString(), anyMap());
  }

  @Test
  void notifierVersion_aucunAbonnementOptIn_retourneZero() {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
    final NotificationVersionPushService service =
        new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> mock(ClientWebPush.class));

    final int nbEnvoyes = service.notifierVersion("3.0.0");

    assertEquals(0, nbEnvoyes);
  }

  @Test
  void notifierVersion_abonnementOptInEtEnvoiReussi_retourneUnEtLogueLeBilan() throws Exception {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnementTest()));

    final ClientWebPush pushServiceMock = mock(ClientWebPush.class);
    when(pushServiceMock.envoyer(any(), any(), any(), any()))
        .thenReturn(new ClientWebPush.Reponse(201, ""));

    final NotificationVersionPushService service =
        new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock);

    final int nbEnvoyes = service.notifierVersion("3.0.0");

    assertEquals(1, nbEnvoyes);
    verify(logsAdminWorker)
        .inserer(org.mockito.ArgumentMatchers.eq("info"), anyString(), anyString(), anyMap());
  }

  @Test
  void notifierVersion_envoiEchoue_retourneZeroSansException() throws Exception {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnementTest()));

    final ClientWebPush pushServiceMock = mock(ClientWebPush.class);
    when(pushServiceMock.envoyer(any(), any(), any(), any()))
        .thenReturn(new ClientWebPush.Reponse(500, ""));

    final NotificationVersionPushService service =
        new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock);

    final int nbEnvoyes = service.notifierVersion("3.0.0");

    assertEquals(0, nbEnvoyes);
    verify(jdbc).queryForMap(contains("echecs_consecutifs + 1"), any(SqlParameterSource.class));
  }

  @Test
  void notifierVersion_abonnementSupprime_revoqueLAppareil() throws Exception {
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(abonnementTest()));
    final ClientWebPush pushServiceMock = mock(ClientWebPush.class);
    when(pushServiceMock.envoyer(any(), any(), any(), any()))
        .thenReturn(new ClientWebPush.Reponse(410, ""));

    new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock)
        .notifierVersion("3.0.0");

    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
  }
}
