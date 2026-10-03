/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.core.ConfigCipher;
import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.admin.LogAdminService;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Test d'intégration de {@link RechiffrementSecretConfig} avec PostgreSQL réel : rotation de
 * {@code APP_CONFIG_SECRET} sans ressaisie ni perte de la 2FA des comptes.
 *
 * @since 3.0.1
 * @author Kwaite
 */
class RechiffrementSecretConfigIntegrationTest extends AbstractWebIntegrationTest {

  private static final String ANCIEN = "ancien-secret-de-test";
  private static final String NOUVEAU = "nouveau-secret-de-test";

  private static NamedParameterJdbcTemplate jdbc;

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
    jdbc.update("DELETE FROM app_config WHERE cle LIKE 'TEST_ROTATION_%'", Map.of());
    jdbc.update("DELETE FROM users WHERE id LIKE 'rotation-%'", Map.of());
  }

  private RechiffrementSecretConfig runner(final String secret, final String precedent) {
    return new RechiffrementSecretConfig(
        jdbc, new LogAdminService(jdbc, new ObjectMapper()), secret, precedent);
  }

  private String valeurConfig(final String cle) {
    return jdbc.queryForObject(
        "SELECT valeur_chiffree FROM app_config WHERE cle = :cle",
        Map.of("cle", cle),
        String.class);
  }

  @Test
  void run_rotationEnCours_rechiffreAppConfigEtTotpAvecLeNouveauSecret() {
    final ConfigCipher ancien = new ConfigCipher(ANCIEN);
    jdbc.update(
        "INSERT INTO app_config (cle, valeur_chiffree) VALUES ('TEST_ROTATION_CLE', :v)",
        Map.of("v", ancien.chiffrer("cle-api-tm")));
    jdbc.update(
        "INSERT INTO app_config (cle, valeur_chiffree) VALUES ('TEST_ROTATION_QUOTA',"
            + " '{\"dispo\":5}')",
        Map.of());
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username, two_factor_secret) VALUES"
            + " ('rotation-1', 'rotation@test.fr', CAST('user' AS role_utilisateur), 'rotation',"
            + " :s)",
        Map.of("s", ancien.chiffrer("JBSWY3DPEHPK3PXP")));

    runner(NOUVEAU, ANCIEN).run(null);

    final ConfigCipher nouveau = new ConfigCipher(NOUVEAU);
    assertEquals("cle-api-tm", nouveau.dechiffrer(valeurConfig("TEST_ROTATION_CLE")));
    assertEquals("{\"dispo\":5}", valeurConfig("TEST_ROTATION_QUOTA"), "valeur en clair intacte");
    assertEquals(
        "JBSWY3DPEHPK3PXP",
        nouveau.dechiffrer(
            jdbc.queryForObject(
                "SELECT two_factor_secret FROM users WHERE id = 'rotation-1'",
                Map.of(),
                String.class)));
  }

  // Relancé après une rotation déjà appliquée (APP_CONFIG_SECRET_PRECEDENT encore défini),
  // rien ne doit changer : les valeurs ne se déchiffrent plus avec l'ancien secret.
  @Test
  void run_relanceApresRotation_nAltereRien() {
    final String dejaRechiffree = new ConfigCipher(NOUVEAU).chiffrer("cle-api-tm");
    jdbc.update(
        "INSERT INTO app_config (cle, valeur_chiffree) VALUES ('TEST_ROTATION_CLE', :v)",
        Map.of("v", dejaRechiffree));

    runner(NOUVEAU, ANCIEN).run(null);

    assertEquals(dejaRechiffree, valeurConfig("TEST_ROTATION_CLE"));
  }
}
