/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.config.ConfigService;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Réglage du plafond d'invitations par un administrateur, sur PostgreSQL réel.
 *
 * @since 3.1.4
 * @author Kwaite
 */
class InvitationAdminServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private LogAdminService logAdminService;
  private ConfigService configService;
  private InvitationAdminService service;

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
  void preparer() {
    jdbc.update("DELETE FROM users", Map.of());
    jdbc.update(
        "INSERT INTO users (id, email, email_verified, role, username, display_username,"
            + " must_change_credentials, created_at, updated_at) VALUES ('u1', 'u1@exemple.fr',"
            + " true, 'user', 'u1', 'u1', false, now(), now())",
        Map.of());
    logAdminService = mock(LogAdminService.class);
    configService = mock(ConfigService.class);
    when(configService.lireConfig(anyString())).thenReturn(Optional.empty());
    service =
        new InvitationAdminService(jdbc, new ReglagesInvitations(configService), logAdminService);
  }

  private Integer plafond() {
    return jdbc.queryForObject(
        "SELECT plafond_invitations FROM users WHERE id = 'u1'", Map.of(), Integer.class);
  }

  @Test
  void reinitialiserEnvois_apresDesEnvois_viderLeJournalDeCadence() {
    jdbc.update("INSERT INTO envois_invitations (parrain_id) VALUES ('u1')", Map.of());
    jdbc.update("INSERT INTO envois_invitations (parrain_id) VALUES ('u1')", Map.of());

    service.reinitialiserEnvois();

    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM envois_invitations", Map.of(), Integer.class));
  }

  @Test
  void definirPlafondUtilisateur_valeurEtRetourAuDefaut_sEnregistreEtEstTracee() {
    service.definirPlafondUtilisateur("u1", 0, "admin1");
    assertEquals(0, plafond());

    service.definirPlafondUtilisateur("u1", null, "admin1");
    assertNull(plafond());
    verify(logAdminService, times(2)).logAdmin(any(), any(), any(), any());
  }

  @Test
  void definirPlafondUtilisateur_valeurHorsBornes_estRefusee() {
    assertThrows(ErreurMetier.class, () -> service.definirPlafondUtilisateur("u1", -1, "admin1"));
    assertThrows(
        ErreurMetier.class,
        () ->
            service.definirPlafondUtilisateur("u1", ReglagesInvitations.PLAFOND_MAX + 1, "admin1"));
  }

  @Test
  void definirPlafondUtilisateur_utilisateurInconnu_estIntrouvable() {
    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> service.definirPlafondUtilisateur("inconnu", 3, "admin1"));

    assertEquals(404, erreur.getStatus());
  }

  @Test
  void definirPlafondDefaut_valeur_estSauvegardeeEtTracee() {
    service.definirPlafondDefaut(4, "admin1");

    verify(configService).sauvegarderConfig(ReglagesInvitations.CLE_PLAFOND_DEFAUT, "4");
    verify(logAdminService).logAdmin(any(), any(), any(), any());
  }

  @Test
  void reglages_sansConfiguration_donneLesValeursParDefaut() {
    assertEquals(
        new InvitationAdminService.Reglages(ReglagesInvitations.PLAFOND_DEFAUT, 5, 30),
        service.reglages());
  }
}
