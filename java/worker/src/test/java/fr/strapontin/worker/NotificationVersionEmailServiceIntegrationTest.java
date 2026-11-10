/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Annonce de nouvelle version par email, sur base réelle : seulement à qui n'a aucun appareil push,
 * une fois par version quel que soit le canal, jamais en fréquence « jamais ».
 *
 * @since 3.1.4
 * @author Kwaite
 */
class NotificationVersionEmailServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private EmailCompteService emailCompteService;
  private NotificationVersionEmailService service;

  @BeforeEach
  void preparer() {
    jdbc.update("DELETE FROM notifications_version", Map.of());
    jdbc.update("DELETE FROM push_abonnements", Map.of());
    jdbc.update("DELETE FROM user_config WHERE user_id LIKE 'vem-%'", Map.of());
    jdbc.update("DELETE FROM users WHERE id LIKE 'vem-%'", Map.of());
    emailCompteService = mock(EmailCompteService.class);
    when(emailCompteService.envoyerNouvelleVersion(any(), any())).thenReturn(true);
    service = new NotificationVersionEmailService(jdbc, emailCompteService, logsAdminWorker);
  }

  private void compte(final String id, final String frequence, final boolean nouvelleVersion) {
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES (:id, :id, :email)",
        Map.of("id", id, "email", id + "@test.fr"));
    jdbc.update(
        "INSERT INTO user_config (user_id, frequence_notifications, notif_nouvelle_version)"
            + " VALUES (:id, CAST(:frequence AS frequence_notif), :nv)",
        Map.of("id", id, "frequence", frequence, "nv", nouvelleVersion));
  }

  private void appareil(final String id) {
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth) VALUES (:id,"
            + " 'https://fcm.googleapis.com/fcm/send/' || :id, 'cle', 'auth')",
        Map.of("id", id));
  }

  @Test
  void notifierVersion_utilisateurSansAppareilPush_estPrevenuParEmailUneSeuleFois() {
    compte("vem-1", "quotidien", true);

    assertEquals(1, service.notifierVersion("3.1.4"));
    assertEquals(0, service.notifierVersion("3.1.4"));

    verify(emailCompteService, times(1)).envoyerNouvelleVersion("vem-1@test.fr", "3.1.4");
  }

  @Test
  void notifierVersion_utilisateurAvecAppareilPush_nEstPasPrevenuParEmail() {
    compte("vem-1", "quotidien", true);
    appareil("vem-1");

    assertEquals(0, service.notifierVersion("3.1.4"));

    verify(emailCompteService, never()).envoyerNouvelleVersion(any(), any());
  }

  @Test
  void notifierVersion_sansOptInOuEnFrequenceJamais_estEcarte() {
    compte("vem-1", "quotidien", false);
    compte("vem-2", "jamais", true);

    assertEquals(0, service.notifierVersion("3.1.4"));
  }

  @Test
  void notifierVersion_dejaPreveuParPushPourCetteVersion_neRecoitPasDEmail() {
    compte("vem-1", "quotidien", true);
    jdbc.update(
        "INSERT INTO notifications_version (user_id, version) VALUES ('vem-1', '3.1.4')", Map.of());

    assertEquals(0, service.notifierVersion("3.1.4"));
  }

  @Test
  void notifierVersion_emailNonParti_laissePourLeProchainCycle() {
    compte("vem-1", "quotidien", true);
    when(emailCompteService.envoyerNouvelleVersion(any(), eq("3.1.4"))).thenReturn(false);

    assertEquals(0, service.notifierVersion("3.1.4"));
    when(emailCompteService.envoyerNouvelleVersion(any(), eq("3.1.4"))).thenReturn(true);

    assertEquals(1, service.notifierVersion("3.1.4"));
  }
}
