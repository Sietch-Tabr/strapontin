/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link NotificationPushDigestService} — révocation
 * d'un abonnement en échec 403 persistant.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class NotificationPushDigestServiceTest {

  static {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private static final String P256DH =
      "BPKi4XnOYaPYQWcKZsUfMxTQZV0JyJA-zx65BGYqeHJ8KDlO2AL5lhoFUCXC1iFMUlTzt_sgos9GiYMxFEgCDQE";
  private static final String AUTH = "RZIK8q0lRcIbLO9VyII_4Q";

  private static ConfigServiceWorker configServiceAvecVapid() {
    final ConfigServiceWorker configService = mock(ConfigServiceWorker.class);
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("cle-publique"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("cle-privee"));
    when(configService.lireConfig("PUBLIC_BASE_URL"))
        .thenReturn(Optional.of("https://strapontin.fr"));
    when(configService.lireConfig("CONTACT_EMAIL"))
        .thenReturn(Optional.of("contact@strapontin.fr"));
    return configService;
  }

  private static Map<String, Object> abonnement(final Object id) {
    final Map<String, Object> abonnement = new HashMap<>();
    abonnement.put("id", id);
    abonnement.put("endpoint", "https://push.exemple.test/abonnement-xyz");
    abonnement.put("p256dh", P256DH);
    abonnement.put("auth", AUTH);
    return abonnement;
  }

  private static PushService pushServiceRetournant(final int statutHttp) throws Exception {
    final PushService pushServiceMock = mock(PushService.class);
    final HttpResponse reponseMock = mock(HttpResponse.class);
    final StatusLine statusLineMock = mock(StatusLine.class);
    when(statusLineMock.getStatusCode()).thenReturn(statutHttp);
    when(reponseMock.getStatusLine()).thenReturn(statusLineMock);
    when(pushServiceMock.send(any(Notification.class))).thenReturn(reponseMock);
    return pushServiceMock;
  }

  private static List<NotificationDigestService.ConcertNotif> unConcert() {
    return List.of(
        new NotificationDigestService.ConcertNotif(
            "Mika",
            "2026-10-01",
            "Accor Arena",
            "Paris",
            "/concerts/1",
            "c1",
            "mika",
            null,
            "onsale"));
  }

  // #1219 : un 403 isolé peut être transitoire côté FCM — ne doit jamais révoquer seul.
  @Test
  void envoyerPush_403UneFois_neRevoquePasLAbonnement() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(contains("push_abonnements"), anyMap()))
        .thenReturn(List.of(abonnement(1)));
    when(jdbc.queryForObject(contains("echecs_403_consecutifs"), anyMap(), eq(Integer.class)))
        .thenReturn(1);
    final LogsAdminWorker logsAdminWorker = mock(LogsAdminWorker.class);
    final PushService pushServiceMock = pushServiceRetournant(403);

    final NotificationPushDigestService service =
        new NotificationPushDigestService(
            configServiceAvecVapid(), jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock);

    service.envoyerPush("u1", unConcert());

    verify(jdbc, never()).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  // Trois cycles consécutifs en 403 (SEUIL_ECHECS_403_ABONNEMENT_MORT) — révocation attendue,
  // journalisée (jamais silencieuse, contrairement à 404/410).
  @Test
  void envoyerPush_403TroisFoisConsecutifs_revoqueEtSignale() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(contains("push_abonnements"), anyMap()))
        .thenReturn(List.of(abonnement(1)));
    when(jdbc.queryForObject(contains("echecs_403_consecutifs"), anyMap(), eq(Integer.class)))
        .thenReturn(3);
    final LogsAdminWorker logsAdminWorker = mock(LogsAdminWorker.class);
    final PushService pushServiceMock = pushServiceRetournant(403);

    final NotificationPushDigestService service =
        new NotificationPushDigestService(
            configServiceAvecVapid(), jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock);

    service.envoyerPush("u1", unConcert());

    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(logsAdminWorker).inserer(eq("warn"), eq("notifier-digest"), anyString(), anyMap());
  }

  // Un envoi réussi doit remettre le compteur à zéro, pas seulement l'ignorer — sans quoi un 403
  // isolé un jour puis un autre 403 isolé une semaine plus tard finiraient par s'additionner à
  // tort en "3 consécutifs".
  @Test
  void envoyerPush_succes_reinitialiseLeCompteur403() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(contains("push_abonnements"), anyMap()))
        .thenReturn(List.of(abonnement(1)));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    final LogsAdminWorker logsAdminWorker = mock(LogsAdminWorker.class);
    final PushService pushServiceMock = pushServiceRetournant(201);

    final NotificationPushDigestService service =
        new NotificationPushDigestService(
            configServiceAvecVapid(), jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock);

    final boolean envoye = service.envoyerPush("u1", unConcert());

    assertTrue(envoye);
    verify(jdbc).update(contains("echecs_403_consecutifs = 0"), anyMap());
  }
}
