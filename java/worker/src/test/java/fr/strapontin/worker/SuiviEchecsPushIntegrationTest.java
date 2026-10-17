/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.sources.TicketmasterSource;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Suivi des envois push en échec sur base réelle : l'échec est daté dès le premier envoi refusé
 * et effacé par un envoi réussi.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class SuiviEchecsPushIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logsAdminWorker;

  private SuiviEchecsPush suivi;
  private Map<String, Object> abonnement;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM push_abonnements", Map.of());
    jdbc.update("DELETE FROM users WHERE id = 'u-push'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u-push', 'u-push', 'u@test.fr')",
        Map.of());
    final UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO push_abonnements (id, user_id, endpoint, p256dh, auth)"
            + " VALUES (CAST(:id AS uuid), 'u-push', 'https://fcm.googleapis.com/x', 'k', 'a')",
        Map.of("id", id.toString()));
    abonnement = Map.of("id", id, "endpoint", "https://fcm.googleapis.com/x");
    suivi = new SuiviEchecsPush(jdbc, logsAdminWorker);
  }

  private Map<String, Object> ligne() {
    return jdbc.queryForMap(
        "SELECT echecs_consecutifs, dernier_echec_le, dernier_echec_statut FROM push_abonnements",
        Map.of());
  }

  @Test
  void echec_premierRefus_datePuisConserveLeStatutSansRevoquer() {
    suivi.echec(abonnement, "u-push", 403, "refusé", "notification-digest");

    final Map<String, Object> apres = ligne();
    assertEquals(1, apres.get("echecs_consecutifs"));
    assertNotNull(apres.get("dernier_echec_le"));
    assertEquals(403, apres.get("dernier_echec_statut"));
  }

  @Test
  void echec_sansReponse_garderLeStatutVide() {
    suivi.echec(abonnement, "u-push", null, "timeout", "notification-digest");

    assertNotNull(ligne().get("dernier_echec_le"));
    assertNull(ligne().get("dernier_echec_statut"));
  }

  @Test
  void succes_apresUnEchec_effaceLeDernierEchec() {
    suivi.echec(abonnement, "u-push", 403, "refusé", "notification-digest");

    suivi.succes(abonnement);

    final Map<String, Object> apres = ligne();
    assertEquals(0, apres.get("echecs_consecutifs"));
    assertNull(apres.get("dernier_echec_le"));
    assertNull(apres.get("dernier_echec_statut"));
  }
}
