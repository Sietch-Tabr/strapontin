/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.AbstractWorkerIntegrationTest;
import fr.strapontin.worker.LogsAdminWorker;
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

  private void configurerCompte(final boolean email) {
    jdbc.update(
        "INSERT INTO user_config (user_id, notif_artistes, notif_artistes_push,"
            + " notif_nouvelle_version, frequence_notifications)"
            + " VALUES ('u-push', :email, true, true, 'quotidien')"
            + " ON CONFLICT (user_id) DO UPDATE SET notif_artistes = :email,"
            + " notif_artistes_push = true, notif_nouvelle_version = true,"
            + " frequence_notifications = 'quotidien'",
        Map.of("email", email));
  }

  private Map<String, Object> config() {
    return jdbc.queryForMap(
        "SELECT notif_artistes_push, notif_salles_push, notif_nouvelle_version,"
            + " frequence_notifications, dernier_appareil_retire_le"
            + " FROM user_config WHERE user_id = 'u-push'",
        Map.of());
  }

  private int nombreAbonnements() {
    return jdbc.queryForObject("SELECT count(*) FROM push_abonnements", Map.of(), Integer.class);
  }

  private void vieillirSerie(final int echecs, final int jours) {
    jdbc.update(
        "UPDATE push_abonnements SET echecs_consecutifs = :echecs,"
            + " premier_echec_le = now() - :jours * interval '1 day'",
        Map.of("echecs", echecs, "jours", jours));
  }

  @Test
  void echec_cinqEchecsMaisSerieDeMoinsDeCinqJours_neRevoquePas() {
    configurerCompte(true);
    vieillirSerie(9, 2);

    suivi.echec(abonnement, "u-push", 503, "indisponible", "notification-digest");

    assertEquals(1, nombreAbonnements());
  }

  @Test
  void echec_serieDeCinqEnvoisSurCinqJours_revoqueEtDecocheLePush() {
    configurerCompte(true);
    vieillirSerie(4, 6);

    suivi.echec(abonnement, "u-push", 403, "refusé", "notification-digest");

    assertEquals(0, nombreAbonnements());
    final Map<String, Object> apres = config();
    assertEquals(false, apres.get("notif_artistes_push"));
    // L'annonce de nouvelle version passe alors par email : le choix est conservé.
    assertEquals(true, apres.get("notif_nouvelle_version"));
    assertEquals("quotidien", apres.get("frequence_notifications"));
    assertNotNull(apres.get("dernier_appareil_retire_le"));
  }

  @Test
  void revoquer_sansContenuEmail_passeLaFrequenceAJamais() {
    configurerCompte(false);

    suivi.revoquer(abonnement, "u-push", "cause", "HTTP 410", "notification-digest");

    assertEquals("jamais", config().get("frequence_notifications"));
    assertEquals(false, config().get("notif_artistes_push"));
  }

  @Test
  void revoquer_autreAppareilRestant_neDecochePasLePush() {
    configurerCompte(true);
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)"
            + " VALUES ('u-push', 'https://updates.push.services.mozilla.com/y', 'k', 'a')",
        Map.of());

    suivi.revoquer(abonnement, "u-push", "cause", "HTTP 410", "notification-digest");

    assertEquals(true, config().get("notif_artistes_push"));
  }

  private void ajouterAbonnementAutreCompte(final boolean enEchec, final boolean aReussi) {
    jdbc.update("DELETE FROM users WHERE id = 'u-autre'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u-autre', 'u-autre', 'a@test.fr')",
        Map.of());
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth, echecs_consecutifs,"
            + " premier_echec_le, dernier_envoi_reussi_le) VALUES ('u-autre',"
            + " 'https://fcm.googleapis.com/z', 'k', 'a', :echecs,"
            + " now() - interval '6 days', CASE WHEN :reussi THEN now() END)",
        Map.of("echecs", enEchec ? 7 : 0, "reussi", aReussi));
  }

  @Test
  void echec_toutLeServiceEchoue_neRevoquePasUnAppareil() {
    configurerCompte(true);
    ajouterAbonnementAutreCompte(true, false);
    jdbc.update(
        "UPDATE push_abonnements SET premier_echec_le = now() - interval '6 days',"
            + " echecs_consecutifs = 4 WHERE user_id = 'u-push'",
        Map.of());

    suivi.echec(abonnement, "u-push", 403, "refusé", "notification-digest");

    assertEquals(2, nombreAbonnements());
  }

  @Test
  void echec_unAutreAppareilDuServiceAReussi_revoqueLAppareilEnEchec() {
    configurerCompte(true);
    ajouterAbonnementAutreCompte(false, true);
    jdbc.update(
        "UPDATE push_abonnements SET premier_echec_le = now() - interval '6 days',"
            + " echecs_consecutifs = 4 WHERE user_id = 'u-push'",
        Map.of());

    suivi.echec(abonnement, "u-push", 403, "refusé", "notification-digest");

    assertEquals(1, nombreAbonnements());
  }

  @Test
  void succes_datePuisReinitialiseLaSerie() {
    vieillirSerie(3, 2);

    suivi.succes(abonnement);

    final Map<String, Object> apres =
        jdbc.queryForMap(
            "SELECT premier_echec_le, dernier_envoi_reussi_le FROM push_abonnements", Map.of());
    assertNull(apres.get("premier_echec_le"));
    assertNotNull(apres.get("dernier_envoi_reussi_le"));
  }
}
