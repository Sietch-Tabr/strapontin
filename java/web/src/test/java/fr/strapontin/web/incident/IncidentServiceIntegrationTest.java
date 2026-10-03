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

import fr.strapontin.web.AbstractWebIntegrationTest;
import fr.strapontin.web.ErreurMetier;
import fr.strapontin.web.admin.LogAdminService;
import fr.strapontin.web.email.EmailServiceSecurite;
import fr.strapontin.web.push.NotificationPushService;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Test d'intégration de {@link IncidentService} avec PostgreSQL réel.
 *
 * @since 3.0.1
 * @author Kwaite
 */
class IncidentServiceIntegrationTest extends AbstractWebIntegrationTest {

  private static final String SESSION_ADMIN = "11111111-1111-1111-1111-111111111111";
  private static final String SESSION_AUTRE = "22222222-2222-2222-2222-222222222222";

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
    jdbc.update("DELETE FROM SPRING_SESSION", Map.of());
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
    service.publier(requete(false, false, false), "admin", null);
    service.publier(requete(false, false, false), "admin", null);

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

    final CommunicationIncident publiee =
        service.publier(requete(true, true, false), "admin", null);

    assertEquals(3, publiee.nbEmails());
    assertEquals(2, publiee.nbPush());
    assertEquals(3, service.lireActive().orElseThrow().nbEmails());
  }

  // Un envoi partiel laisserait des comptes sans nouvelles : refusé d'emblée, sans rien publier.
  @Test
  void publier_quotaEmailInsuffisant_refuseSansRienPublier() {
    when(emailService.envoisEmailRestants()).thenReturn(0);

    final ErreurMetier erreur =
        assertThrows(
            ErreurMetier.class, () -> service.publier(requete(true, false, false), "a", null));

    assertEquals(409, erreur.getStatus());
    assertFalse(service.lireActive().isPresent());
    verify(emailService, never())
        .envoyerCommunicationIncident(anyList(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void publier_changementMotDePasseForce_exigeUnNouveauMotDePasseDeTousLesComptes() {
    service.publier(requete(false, false, true), "admin", null);

    final Integer sansFlag =
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE changement_mdp_force_le IS NULL",
            Map.of(),
            Integer.class);
    assertEquals(0, sansFlag);
  }

  @Test
  void publier_changementMotDePasseForce_revoqueToutesLesSessionsSaufCelleDeLAdmin() {
    for (final String sessionId : List.of(SESSION_ADMIN, SESSION_AUTRE)) {
      jdbc.update(
          "INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,"
              + " MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)"
              + " VALUES (:id, :id, 0, 0, 2592000, 9999999999999, 'quelquun')",
          Map.of("id", sessionId));
    }

    service.publier(requete(false, false, true), "admin", SESSION_ADMIN);

    assertEquals(
        List.of(SESSION_ADMIN),
        jdbc.queryForList("SELECT trim(SESSION_ID) FROM SPRING_SESSION", Map.of(), String.class));
  }

  @Test
  void publier_sansChangementMotDePasse_neRevoqueAucuneSession() {
    jdbc.update(
        "INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME,"
            + " MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME)"
            + " VALUES (:id, :id, 0, 0, 2592000, 9999999999999, 'quelquun')",
        Map.of("id", SESSION_AUTRE));

    service.publier(requete(false, false, false), "admin", SESSION_ADMIN);

    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM SPRING_SESSION", Map.of(), Integer.class));
  }

  @Test
  void terminer_communicationActive_laClotEtNeLaPlusLire() {
    service.publier(requete(false, false, false), "admin", null);

    assertTrue(service.terminer());
    assertFalse(service.lireActive().isPresent());
    assertFalse(service.terminer(), "plus rien à clore");
  }
}
