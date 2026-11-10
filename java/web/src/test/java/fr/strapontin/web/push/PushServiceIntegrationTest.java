/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Abonnements push sur PostgreSQL réel : appareil nommé à l'abonnement, échec daté visible dans la
 * liste de l'utilisateur.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class PushServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private PushService service;

  @BeforeAll
  static void migrerBase() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM push_abonnements", Map.of());
    jdbc.update("DELETE FROM users WHERE id = 'u-push'", Map.of());
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES ('u-push', 'u-push', 'p@test.fr')",
        Map.of());
    service = new PushService(mock(ConfigService.class), jdbc, mock(LogAdminService.class));
    service.setSelf(service);
  }

  private static RequeteAbonnement requete(final String chemin) {
    return new RequeteAbonnement(
        "https://fcm.googleapis.com/fcm/send/" + chemin, new ClesAbonnement("p256dh", "auth"));
  }

  @Test
  void listerAbonnements_apresAbonnement_nommeLAppareil() {
    service.abonner("u-push", requete("a"), "Chrome sur Android");

    final List<PushService.AbonnementDto> liste = service.listerAbonnements("u-push");

    assertEquals(1, liste.size());
    assertEquals("Chrome sur Android", liste.get(0).appareil());
    assertNull(liste.get(0).enEchecDepuis());
    assertNull(liste.get(0).cause());
  }

  @Test
  void abonner_memeAppareilSansNom_conserveLeNomDejaConnu() {
    service.abonner("u-push", requete("a"), "Firefox sur Linux");

    service.abonner("u-push", requete("a"), null);

    assertEquals("Firefox sur Linux", service.listerAbonnements("u-push").get(0).appareil());
  }

  @Test
  void listerAbonnements_apresUnRefusDuServicePush_exposeLEchecEtSaCause() {
    service.abonner("u-push", requete("a"), "Chrome sur Windows");
    jdbc.update(
        "UPDATE push_abonnements SET echecs_consecutifs = 1, dernier_echec_le = now(),"
            + " dernier_echec_statut = 403",
        Map.of());

    final PushService.AbonnementDto abonnement = service.listerAbonnements("u-push").get(0);

    assertNotNull(abonnement.enEchecDepuis());
    assertTrue(abonnement.cause().contains("refusé"));
  }

  @Test
  void listerAbonnements_echecSansReponse_exposeLaCauseInjoignable() {
    service.abonner("u-push", requete("a"), null);
    jdbc.update(
        "UPDATE push_abonnements SET echecs_consecutifs = 1, dernier_echec_le = now(),"
            + " dernier_echec_statut = NULL",
        Map.of());

    assertTrue(service.listerAbonnements("u-push").get(0).cause().contains("injoignable"));
  }

  private void configurer(final boolean retire) {
    jdbc.update(
        "INSERT INTO user_config (user_id, notif_artistes, notif_salles, notif_artistes_push,"
            + " notif_nouvelle_version, frequence_notifications, dernier_appareil_retire_le,"
            + " cause_dernier_retrait_appareil)"
            + " VALUES ('u-push', false, false, true, true, 'quotidien',"
            + " CASE WHEN :retire THEN now() END, CASE WHEN :retire THEN 'cause' END)"
            + " ON CONFLICT (user_id) DO UPDATE SET notif_artistes = false, notif_salles = false,"
            + " notif_artistes_push = true,"
            + " notif_nouvelle_version = true, frequence_notifications = 'quotidien',"
            + " dernier_appareil_retire_le = CASE WHEN :retire THEN now() END,"
            + " cause_dernier_retrait_appareil = CASE WHEN :retire THEN 'cause' END",
        Map.of("retire", retire));
  }

  @Test
  void abonner_apresUnRetrait_effaceLeRetraitPourQueLAvertissementNeRevienneJamais() {
    configurer(true);

    service.abonner("u-push", requete("a"), null);

    assertNull(
        jdbc.queryForObject(
            "SELECT dernier_appareil_retire_le FROM user_config WHERE user_id = 'u-push'",
            Map.of(),
            OffsetDateTime.class));
  }

  @Test
  void desabonner_dernierAppareil_decocheLePushSansAvertissement() {
    service.abonner("u-push", requete("a"), null);
    configurer(false);

    service.desabonner("u-push", "https://fcm.googleapis.com/fcm/send/a");

    final Map<String, Object> config =
        jdbc.queryForMap(
            "SELECT notif_artistes_push, notif_nouvelle_version, frequence_notifications,"
                + " dernier_appareil_retire_le FROM user_config WHERE user_id = 'u-push'",
            Map.of());
    assertEquals(false, config.get("notif_artistes_push"));
    assertEquals(true, config.get("notif_nouvelle_version"));
    assertEquals("jamais", config.get("frequence_notifications"));
    assertNull(config.get("dernier_appareil_retire_le"));
  }
}
