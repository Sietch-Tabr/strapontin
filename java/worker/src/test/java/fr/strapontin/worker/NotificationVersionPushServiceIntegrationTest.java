/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ClientWebPush;
import fr.strapontin.core.FournisseurBouncyCastle;
import fr.strapontin.sources.TicketmasterSource;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Annonce de nouvelle version sur base réelle : une seule fois par version et par utilisateur,
 * jamais à qui a choisi « Jamais ».
 *
 * @since 3.0.6
 * @author Kwaite
 */
class NotificationVersionPushServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private ClientWebPush pushService;
  private NotificationVersionPushService service;

  @BeforeEach
  void setUp() throws Exception {
    // Le fournisseur est enregistré au démarrage du worker, pas par le contexte de test.
    FournisseurBouncyCastle.garantir();
    jdbc.update("DELETE FROM notifications_version", Map.of());
    jdbc.update("DELETE FROM push_abonnements", Map.of());
    jdbc.update("DELETE FROM user_config WHERE user_id LIKE 'ver-%'", Map.of());
    jdbc.update("DELETE FROM users WHERE id LIKE 'ver-%'", Map.of());
    final ConfigServiceWorker configService = mock(ConfigServiceWorker.class);
    when(configService.lireConfig("VAPID_PUBLIC_KEY")).thenReturn(Optional.of("pub"));
    when(configService.lireConfig("VAPID_PRIVATE_KEY")).thenReturn(Optional.of("priv"));
    pushService = mock(ClientWebPush.class);
    when(pushService.envoyer(any(), any(), any(), any()))
        .thenReturn(new ClientWebPush.Reponse(201, ""));
    service =
        new NotificationVersionPushService(
            configService, jdbc, logsAdminWorker, (_, _, _) -> pushService);
  }

  private void compte(final String id, final String frequence, final boolean nouvelleVersion) {
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES (:id, :id, :email)",
        Map.of("id", id, "email", id + "@test.fr"));
    jdbc.update(
        "INSERT INTO user_config (user_id, frequence_notifications, notif_nouvelle_version)"
            + " VALUES (:id, CAST(:frequence AS frequence_notif), :nv)",
        Map.of("id", id, "frequence", frequence, "nv", nouvelleVersion));
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth) VALUES (:id,"
            + " 'https://fcm.googleapis.com/fcm/send/' || :id,"
            + " 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM',"
            + " 'tBHItJI5svbpez7KI4CCXg')",
        Map.of("id", id));
  }

  @Test
  void notifierVersion_deuxEnvoisDeLaMemeVersion_neNotifiePasDeuxFoisLeMemeUtilisateur()
      throws Exception {
    compte("ver-1", "quotidien", true);

    assertEquals(1, service.notifierVersion("3.0.6"));
    assertEquals(0, service.notifierVersion("3.0.6"));

    verify(pushService, times(1)).envoyer(any(), any(), any(), any());
  }

  @Test
  void notifierVersion_versionSuivante_notifieDeNouveau() {
    compte("ver-1", "quotidien", true);
    service.notifierVersion("3.0.6");

    assertEquals(1, service.notifierVersion("3.0.7"));
  }

  @Test
  void notifierVersion_utilisateurSansOptInOuEnFrequenceJamais_estEcarte() {
    compte("ver-1", "quotidien", false);
    compte("ver-2", "jamais", true);

    assertEquals(0, service.notifierVersion("3.0.6"));
  }

  @Test
  void notifierVersion_utilisateurDejaNotifieEtNouvelUtilisateur_neNotifieQueLeNouveau() {
    compte("ver-1", "quotidien", true);
    service.notifierVersion("3.0.6");
    compte("ver-2", "hebdomadaire", true);

    assertEquals(1, service.notifierVersion("3.0.6"));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications_version WHERE version = '3.0.6'",
            Map.of(),
            Long.class));
  }
}
