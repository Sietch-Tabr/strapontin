/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.TicketmasterSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/// Tests d'intégration de [WorkerFetchService] avec PostgreSQL réel (Testcontainers).
///
/// <p>Couvre spécifiquement la restriction de {@code fetch:salles:all} aux salles suivies
/// (#996) — le filtre {@code EXISTS (... FROM user_salles ...)} de la requête SQL ne peut être
/// vérifié qu'avec une vraie base, les tests unitaires de {@link WorkerFetchServiceTest} mockant
/// {@code jdbc} avec un matcher {@code anyString()} qui ne distingue pas les variantes de la
/// requête.
///
/// @since 2.7.3
/// @author Kwaite
@SpringBootTest
@ActiveProfiles("test")
class WorkerFetchServiceIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

  static {
    postgres.start();
  }

  @MockitoBean private TicketmasterSource ticketmasterSource;

  @Autowired private WorkerFetchService fetchService;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void nettoyerBase() {
    jdbc.update("DELETE FROM user_matches", Map.of());
    jdbc.update("DELETE FROM user_salles", Map.of());
    jdbc.update("DELETE FROM salle_sources", Map.of());
    jdbc.update("DELETE FROM concerts", Map.of());
    jdbc.update("DELETE FROM salles", Map.of());
    jdbc.update("DELETE FROM users", Map.of());
  }

  private void insererUser(String id) {
    jdbc.update(
        "INSERT INTO users (id, name, email) VALUES (:id, :name, :email)",
        Map.of("id", id, "name", "User " + id, "email", id + "@test.fr"));
  }

  private UUID insererSalleAvecSourceTm(String salleCle, String venueId) {
    UUID salleId =
        jdbc.queryForObject(
            "INSERT INTO salles (salle_cle, nom, nom_norm) VALUES (:id, :nom, :norm)"
                + " ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom RETURNING id",
            Map.of("id", salleCle, "nom", salleCle, "norm", salleCle),
            (rs, _) -> rs.getObject("id", UUID.class));
    jdbc.update(
        "INSERT INTO salle_sources (salle_id, source, identifiant_externe)"
            + " VALUES (CAST(:salleId AS uuid), 'ticketmaster', :venueId)",
        Map.of("salleId", salleId.toString(), "venueId", venueId));
    return salleId;
  }

  private void insererUserSalle(String userId, UUID salleId) {
    jdbc.update(
        "INSERT INTO user_salles (user_id, salle_id) VALUES (:userId, CAST(:salleId AS uuid))",
        Map.of("userId", userId, "salleId", salleId.toString()));
  }

  @Test
  void executerFetchSallesAll_salleSuivie_estFetchee() {
    insererUser("u1");
    UUID salleSuivie = insererSalleAvecSourceTm("salle-suivie", "venue-suivie");
    insererUserSalle("u1", salleSuivie);
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"))).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource).fetchSalle("tm:venue-suivie");
  }

  @Test
  void executerFetchSallesAll_salleNonSuivie_nEstJamaisFetchee() {
    insererUser("u1");
    insererSalleAvecSourceTm("salle-non-suivie", "venue-non-suivie");
    when(ticketmasterSource.accepteIdentifiantSalle(startsWith("tm:"))).thenReturn(true);
    when(ticketmasterSource.fetchSalle(startsWith("tm:"))).thenReturn(List.<ConcertBrut>of());

    fetchService.executerFetchSallesAll();

    verify(ticketmasterSource, never()).fetchSalle("tm:venue-non-suivie");
  }
}
