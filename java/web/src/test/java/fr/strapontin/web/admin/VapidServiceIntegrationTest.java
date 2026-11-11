/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.config.ConfigService;
import fr.strapontin.web.email.EmailServiceSecurite;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Renouvellement des clés VAPID sur PostgreSQL réel : motif obligatoire, comptes prévenus quel que
 * soit leur autre canal, cause propre au renouvellement.
 *
 * @since 3.0.6
 * @author Kwaite
 */
class VapidServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private ConfigService configService;
  private LogAdminService logAdminService;
  private EmailServiceSecurite emailServiceSecurite;
  private VapidService service;

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
    jdbc.update("DELETE FROM user_config WHERE user_id IN ('v1', 'v2', 'v3')", Map.of());
    jdbc.update("DELETE FROM users WHERE id IN ('v1', 'v2', 'v3')", Map.of());
    configService = mock(ConfigService.class);
    logAdminService = mock(LogAdminService.class);
    emailServiceSecurite = mock(EmailServiceSecurite.class);
    service = new VapidService(configService, jdbc, logAdminService, emailServiceSecurite);
  }

  private void compteAvecAppareil(final String id, final boolean emailActif) {
    jdbc.update(
        "INSERT INTO users (id, username, email) VALUES (:id, :id, :email)",
        Map.of("id", id, "email", id + "@test.fr"));
    jdbc.update(
        "INSERT INTO user_config (user_id, notif_artistes, dernier_appareil_retire_le,"
            + " cause_dernier_retrait_appareil) VALUES (:id, :email,"
            + " now() - interval '10 days', 'cause antérieure')",
        Map.of("id", id, "email", emailActif));
    jdbc.update(
        "INSERT INTO push_abonnements (user_id, endpoint, p256dh, auth)"
            + " VALUES (:id, 'https://fcm.googleapis.com/fcm/send/' || :id, 'k', 'a')",
        Map.of("id", id));
  }

  @Test
  void genererVapid_motifVide_estRefuseSansToucherAuxCles() {
    assertThrows(ErreurMetier.class, () -> service.genererVapid("   "));

    verify(configService, never()).sauvegarderConfig(eq("VAPID_PUBLIC_KEY"), any());
  }

  @Test
  void genererVapid_comptesAvecAppareil_tousPrevenusQuelQueSoitLEmailActif() throws Exception {
    compteAvecAppareil("v1", true);
    compteAvecAppareil("v2", false);
    when(emailServiceSecurite.envoyerPushReinitialise(any(), eq("Fuite de la clé privée")))
        .thenReturn(List.of("v1@test.fr", "v2@test.fr"));

    final VapidService.ResultatVapid resultat = service.genererVapid("Fuite de la clé privée");

    assertEquals(2, resultat.nbAbonnementsSupprimes());
    assertEquals(2, resultat.nbComptesPrevenus());
    assertEquals(0, resultat.nbComptesNonPrevenus());
    assertEquals(
        0L, jdbc.queryForObject("SELECT count(*) FROM push_abonnements", Map.of(), Long.class));
  }

  @Test
  void genererVapid_comptesSansAppareilRestant_decocheLeurPush() throws Exception {
    compteAvecAppareil("v1", true);
    jdbc.update(
        "UPDATE user_config SET notif_artistes_push = true, notif_nouvelle_version = true,"
            + " frequence_notifications = 'quotidien' WHERE user_id = 'v1'",
        Map.of());
    when(emailServiceSecurite.envoyerPushReinitialise(any(), any()))
        .thenReturn(List.of("v1@test.fr"));

    service.genererVapid("Fuite de la clé privée");

    final Map<String, Object> config =
        jdbc.queryForMap(
            "SELECT notif_artistes_push, notif_nouvelle_version, frequence_notifications"
                + " FROM user_config WHERE user_id = 'v1'",
            Map.of());
    assertEquals(false, config.get("notif_artistes_push"));
    assertEquals(true, config.get("notif_nouvelle_version"));
    assertEquals("quotidien", config.get("frequence_notifications"));
  }

  @Test
  void genererVapid_causeEnregistree_estcelleDuRenouvellementEtPasUneCauseAnterieure()
      throws Exception {
    compteAvecAppareil("v1", true);
    when(emailServiceSecurite.envoyerPushReinitialise(any(), any()))
        .thenReturn(List.of("v1@test.fr"));

    service.genererVapid("Fuite de la clé privée");

    final String cause =
        jdbc.queryForObject(
            "SELECT cause_dernier_retrait_appareil FROM user_config WHERE user_id = 'v1'",
            Map.of(),
            String.class);
    assertTrue(cause.contains("renouvelées"));
    assertTrue(cause.contains("Fuite de la clé privée"));
  }

  @Test
  void genererVapid_compteNonPrevenu_estComptePourLAdminEtGardeSonAvertissementGenerique()
      throws Exception {
    compteAvecAppareil("v1", true);
    compteAvecAppareil("v2", true);
    when(emailServiceSecurite.envoyerPushReinitialise(any(), any()))
        .thenReturn(List.of("v1@test.fr"));

    final VapidService.ResultatVapid resultat = service.genererVapid("Rotation annuelle");

    assertEquals(1, resultat.nbComptesPrevenus());
    assertEquals(1, resultat.nbComptesNonPrevenus());
    assertNotNull(
        jdbc.queryForObject(
            "SELECT avertissement_canal_perdu_le FROM user_config WHERE user_id = 'v1'",
            Map.of(),
            java.sql.Timestamp.class));
    assertEquals(
        null,
        jdbc.queryForObject(
            "SELECT avertissement_canal_perdu_le FROM user_config WHERE user_id = 'v2'",
            Map.of(),
            java.sql.Timestamp.class));
  }
}
