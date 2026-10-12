/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import fr.strapontin.core.EchecPush;
import fr.strapontin.core.FournisseurBouncyCastle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link NotificationPushDigestService} : révocation
 * d'un abonnement en échec répété, cause lue dans la réponse.
 *
 * @since 3.0.0
 * @author Kwaite
 */
class NotificationPushDigestServiceTest {

  static {
    FournisseurBouncyCastle.garantir();
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
    abonnement.put("endpoint", "https://fcm.googleapis.com/fcm/send/abonnement-xyz");
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

  private static PushService pushServiceRetournant(final int statutHttp, final String corps)
      throws Exception {
    final PushService pushServiceMock = pushServiceRetournant(statutHttp);
    final HttpResponse reponse = pushServiceMock.send(mock(Notification.class));
    when(reponse.getEntity()).thenReturn(new StringEntity(corps, StandardCharsets.UTF_8));
    return pushServiceMock;
  }

  private static NamedParameterJdbcTemplate jdbcAvecCompteur(final int echecs) {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(contains("push_abonnements"), anyMap()))
        .thenReturn(List.of(abonnement(1)));
    when(jdbc.queryForObject(contains("echecs_consecutifs + 1"), anyMap(), eq(Integer.class)))
        .thenReturn(echecs);
    return jdbc;
  }

  // Un échec isolé peut être transitoire côté service push : jamais de révocation seul.
  @Test
  void envoyerPush_403UneFois_neRevoquePasLAbonnement() throws Exception {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCompteur(1);
    final LogsAdminWorker logsAdminWorker = mock(LogsAdminWorker.class);
    final PushService pushServiceMock = pushServiceRetournant(403);

    new NotificationPushDigestService(
            configServiceAvecVapid(), jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock)
        .envoyerPush("u1", unConcert());

    verify(jdbc, never()).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void envoyerPush_cinquiemeEchecConsecutifHors403_revoqueAvecLaRaisonDuService() throws Exception {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCompteur(EchecPush.SEUIL_ECHECS_CONSECUTIFS);
    final LogsAdminWorker logsAdminWorker = mock(LogsAdminWorker.class);
    final PushService pushServiceMock =
        pushServiceRetournant(429, "{\"reason\":\"TooManyProviderTokenUpdates\"}");

    new NotificationPushDigestService(
            configServiceAvecVapid(), jdbc, logsAdminWorker, (_, _, _) -> pushServiceMock)
        .envoyerPush("u1", unConcert());

    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(jdbc)
        .update(
            contains("cause_dernier_retrait_appareil"),
            eq(Map.of("userId", "u1", "cause", EchecPush.causeLisible(429))));
    final ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.captor();
    verify(logsAdminWorker).inserer(eq("warn"), eq("notifier-digest"), anyString(), meta.capture());
    assertTrue(((String) meta.getValue().get("raison")).contains("TooManyProviderTokenUpdates"));
    assertEquals("https://fcm.googleapis.com/…", meta.getValue().get("service"));
  }

  @Test
  void envoyerPush_exceptionRepetee_revoqueAuSeuil() throws Exception {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCompteur(EchecPush.SEUIL_ECHECS_CONSECUTIFS);
    final PushService pushServiceMock = mock(PushService.class);
    when(pushServiceMock.send(any(Notification.class))).thenThrow(new IOException("timeout"));

    new NotificationPushDigestService(
            configServiceAvecVapid(),
            jdbc,
            mock(LogsAdminWorker.class),
            (_, _, _) -> pushServiceMock)
        .envoyerPush("u1", unConcert());

    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
  }

  @Test
  void envoyerPush_410_revoqueSansCompterLesEchecs() throws Exception {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCompteur(0);
    final PushService pushServiceMock = pushServiceRetournant(410);

    new NotificationPushDigestService(
            configServiceAvecVapid(),
            jdbc,
            mock(LogsAdminWorker.class),
            (_, _, _) -> pushServiceMock)
        .envoyerPush("u1", unConcert());

    verify(jdbc).update(contains("DELETE FROM push_abonnements"), anyMap());
    verify(jdbc, never())
        .queryForObject(contains("echecs_consecutifs + 1"), anyMap(), eq(Integer.class));
  }

  // Sans remise à zéro, deux échecs isolés à des semaines d'écart finiraient par
  // s'additionner en échecs « consécutifs ».
  @Test
  void envoyerPush_succes_reinitialiseLeCompteur() throws Exception {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCompteur(0);
    final PushService pushServiceMock = pushServiceRetournant(201);

    final boolean envoye =
        new NotificationPushDigestService(
                configServiceAvecVapid(),
                jdbc,
                mock(LogsAdminWorker.class),
                (_, _, _) -> pushServiceMock)
            .envoyerPush("u1", unConcert());

    assertTrue(envoye);
    verify(jdbc).update(contains("echecs_consecutifs = 0"), anyMap());
  }
}
