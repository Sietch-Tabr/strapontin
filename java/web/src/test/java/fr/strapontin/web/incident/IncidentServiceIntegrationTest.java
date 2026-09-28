/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.email.EmailServiceSecurite;
import fr.strapontin.web.push.NotificationPushService;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test d'intégration de {@link IncidentService} avec PostgreSQL réel.
 *
 * @since 3.0.1
 * @author Kwaite
 */
class IncidentServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static NamedParameterJdbcTemplate jdbc;

  private EmailServiceSecurite emailService;
  private NotificationPushService pushService;
  private IncidentService service;

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
    jdbc.update("DELETE FROM communications_incident", Map.of());
    jdbc.update("DELETE FROM users WHERE id LIKE 'incident-%'", Map.of());
    jdbc.update("UPDATE users SET changement_mdp_force_le = NULL", Map.of());
    jdbc.update(
        "INSERT INTO users (id, email, role, display_username)"
            + " VALUES ('incident-1', 'incident1@test.fr', CAST('user' AS role_utilisateur), 'i1')",
        Map.of());
    emailService = mock(EmailServiceSecurite.class);
    pushService = mock(NotificationPushService.class);
    service =
        new IncidentService(
            jdbc, emailService, pushService, new LogAdminService(jdbc, new ObjectMapper()));
  }

  private static RequetePublicationIncident requete(
      final boolean email, final boolean push, final boolean mdp) {
    return new RequetePublicationIncident("Incident", "Fuite possible.", email, push, mdp);
  }

  @Test
  void publier_nouvelleCommunication_remplaceLaPrecedenteEtResteSeuleActive() {
    service.publier(requete(false, false, false), "admin");
    service.publier(requete(false, false, false), "admin");

    final Integer actives =
        jdbc.queryForObject(
            "SELECT count(*) FROM communications_incident WHERE terminee_le IS NULL",
            Map.of(),
            Integer.class);
    assertEquals(1, actives);
    assertTrue(service.lireActive().isPresent());
  }

  @Test
  void publier_emailEtPush_diffuseEtMemoriseLesNombresEnvoyes() {
    when(emailService.envoisEmailRestants()).thenReturn(100);
    when(emailService.envoyerCommunicationIncident(
            anyList(), anyString(), anyString(), anyBoolean()))
        .thenReturn(3);
    when(pushService.envoyerATous(anyString(), anyString())).thenReturn(2);

    final CommunicationIncident publiee = service.publier(requete(true, true, false), "admin");

    assertEquals(3, publiee.nbEmails());
    assertEquals(2, publiee.nbPush());
    assertEquals(3, service.lireActive().orElseThrow().nbEmails());
  }

  // Un envoi partiel laisserait des comptes sans nouvelles : refusé d'emblée, sans rien publier.
  @Test
  void publier_quotaEmailInsuffisant_refuseSansRienPublier() {
    when(emailService.envoisEmailRestants()).thenReturn(0);

    final ErreurMetier erreur =
        assertThrows(ErreurMetier.class, () -> service.publier(requete(true, false, false), "a"));

    assertEquals(409, erreur.getStatus());
    assertFalse(service.lireActive().isPresent());
    verify(emailService, never())
        .envoyerCommunicationIncident(anyList(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void publier_changementMotDePasseForce_exigeUnNouveauMotDePasseDeTousLesComptes() {
    service.publier(requete(false, false, true), "admin");

    final Integer sansFlag =
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE changement_mdp_force_le IS NULL",
            Map.of(),
            Integer.class);
    assertEquals(0, sansFlag);
  }

  @Test
  void terminer_communicationActive_laClotEtNeLaPlusLire() {
    service.publier(requete(false, false, false), "admin");

    assertTrue(service.terminer());
    assertFalse(service.lireActive().isPresent());
    assertFalse(service.terminer(), "plus rien à clore");
  }
}
