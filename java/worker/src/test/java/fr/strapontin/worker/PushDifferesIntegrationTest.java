/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.FournisseurBouncyCastle;
import fr.strapontin.sources.TicketmasterSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Plage silencieuse du push sur base réelle : un envoi de nuit est retenu, puis part à 7 h sans
 * perte ni doublon.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class PushDifferesIntegrationTest extends AbstractWorkerIntegrationTest {

  // 23 h 30 à Paris (UTC+2) : dans la plage ; 7 h 00 : la plage est terminée.
  private static final Instant NUIT = Instant.parse("2026-10-17T21:30:00Z");
  private static final Instant SEPT_HEURES = Instant.parse("2026-10-18T05:00:00Z");

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private ConfigServiceWorker configService;
  private PushService pushService;
  private NotificationDigestService.ConcertNotif concert;

  @BeforeEach
  void setUp() throws Exception {
    // Le fournisseur est enregistré au démarrage du worker, pas par le contexte de test.
    FournisseurBouncyCastle.garantir();
    jdbc.update("DELETE FROM push_differes", Map.of());
    jdbc.update("DELETE FROM push_abonnements", Map.of());
    jdbc.update("DELETE FROM evenements", Map.of());
    jdbc.update("DELETE FROM users WHERE id = 'u-nuit'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u-nuit', 'u-nuit', 'n@test.fr')",
        Map.of());
    configService = mock(ConfigServiceWorker.class);
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("pub"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("priv"));
    when(configService.lireConfig("PUBLIC_BASE_URL")).thenReturn(Optional.of("https://exemple.fr"));
    when(configService.lireConfig("CONTACT_EMAIL")).thenReturn(Optional.of("c@exemple.fr"));
    pushService = mock(PushService.class);
    final HttpResponse reponse = mock(HttpResponse.class);
    final StatusLine statut = mock(StatusLine.class);
    when(statut.getStatusCode()).thenReturn(201);
    when(reponse.getStatusLine()).thenReturn(statut);
    when(pushService.send(any(Notification.class), eq(Encoding.AES128GCM))).thenReturn(reponse);

    final UUID id =
        jdbc.queryForObject(
            "INSERT INTO evenements (artiste_norm, date, salle_norm, dept)"
                + " VALUES ('groupe nuit', CURRENT_DATE + 30, 'salle', '75') RETURNING id",
            Map.of(),
            UUID.class);
    jdbc.update(
        "INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut)"
            + " VALUES (CAST(:id AS uuid), CAST('ticketmaster' AS source_concert), 'https://x/1',"
            + " 'Groupe Nuit', 'Salle')",
        Map.of("id", id.toString()));
    concert =
        new NotificationDigestService.ConcertNotif(
            "Groupe Nuit",
            "2026-11-16",
            "Salle",
            "Paris",
            "https://x/1",
            id.toString(),
            "groupe nuit",
            "ARTISTE",
            "onsale");
  }

  private void abonner() {
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)"
            + " VALUES ('u-nuit', 'https://fcm.googleapis.com/fcm/send/abc',"
            + " 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8Q"
            + "cYP7DkM', 'tBHItJI5svbpez7KI4CCXg')",
        Map.of());
  }

  private NotificationPushDigestService service(final Instant instant) {
    return new NotificationPushDigestService(
        configService,
        jdbc,
        logsAdminWorker,
        (_, _, _) -> pushService,
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  private long retenus() {
    return jdbc.queryForObject("SELECT count(*) FROM push_differes", Map.of(), Long.class);
  }

  @Test
  void envoyerPush_enPlageSilencieuse_retientLEnvoiSansRienEnvoyer() throws Exception {
    abonner();

    final boolean pris = service(NUIT).envoyerPush("u-nuit", List.of(concert));

    assertTrue(pris);
    assertEquals(1, retenus());
    verify(pushService, never()).send(any(Notification.class), any(Encoding.class));
  }

  @Test
  void envoyerPush_enPlageSilencieuseSansAppareil_neRetientRien() {
    final boolean pris = service(NUIT).envoyerPush("u-nuit", List.of(concert));

    assertFalse(pris);
    assertEquals(0, retenus());
  }

  @Test
  void envoyerDifferes_aSeptHeures_envoieUneFoisPuisEfface() throws Exception {
    abonner();
    service(NUIT).envoyerPush("u-nuit", List.of(concert));

    final int utilisateurs = service(SEPT_HEURES).envoyerDifferes();

    assertEquals(1, utilisateurs);
    assertEquals(0, retenus());
    verify(pushService).send(any(Notification.class), eq(Encoding.AES128GCM));
    assertEquals(0, service(SEPT_HEURES).envoyerDifferes());
  }

  @Test
  void envoyerDifferes_toujoursDansLaPlage_neToucheARien() throws Exception {
    abonner();
    service(NUIT).envoyerPush("u-nuit", List.of(concert));

    assertEquals(0, service(Instant.parse("2026-10-18T03:00:00Z")).envoyerDifferes());

    assertEquals(1, retenus());
    verify(pushService, never()).send(any(Notification.class), any(Encoding.class));
  }

  @Test
  void envoyerPush_enJournee_envoieImmediatement() throws Exception {
    abonner();

    final boolean pris =
        service(Instant.parse("2026-10-17T12:00:00Z")).envoyerPush("u-nuit", List.of(concert));

    assertTrue(pris);
    assertEquals(0, retenus());
    verify(pushService).send(any(Notification.class), eq(Encoding.AES128GCM));
  }
}
