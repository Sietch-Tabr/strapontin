/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.TicketmasterSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sélection réelle des comptes relancés pour inactivité par {@link WorkerPurgeService}.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class WorkerPurgeServiceIntegrationTest extends AbstractWorkerIntegrationTest {

  // Le bean réel échoue au démarrage sans TICKETMASTER_API_KEY en base.
  @MockitoBean private TicketmasterSource ticketmasterSourceIgnore;
  @MockitoBean private PurgeCompteEmailService purgeCompteEmailService;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private WorkerPurgeService service;

  @Test
  void executerGererComptesInactifs_neRelanceQueLesComptesActivesInactifsDate() {
    creerCompte("inactif", ilYA(210), false);
    creerCompte("actif", ilYA(1), false);
    creerCompte("sansDate", null, false);
    creerCompte("invite", ilYA(210), true);
    when(purgeCompteEmailService.envoyerRelanceInactivite(anyString(), anyInt())).thenReturn(true);

    service.executerGererComptesInactifs();

    assertEquals(
        List.of("inactif"),
        jdbc.queryForList(
            "SELECT id FROM users WHERE purge_le IS NOT NULL ORDER BY id", Map.of(), String.class));
    assertEquals(
        List.of("info"),
        jdbc.queryForList(
            "SELECT niveau FROM logs_admin WHERE source = 'purge-comptes'",
            Map.of(),
            String.class));
  }

  @Test
  void executerGererComptesInactifs_vagueDeRelances_signaleEnWarn() {
    for (int i = 0; i < WorkerPurgeService.SEUIL_VAGUE_COMPTES; i++) {
      creerCompte("inactif" + i, ilYA(210), false);
    }
    when(purgeCompteEmailService.envoyerRelanceInactivite(anyString(), anyInt())).thenReturn(true);

    service.executerGererComptesInactifs();

    assertEquals(
        List.of("warn"),
        jdbc.queryForList(
            "SELECT niveau FROM logs_admin WHERE source = 'purge-comptes'",
            Map.of(),
            String.class));
  }

  private static Timestamp ilYA(final int jours) {
    return Timestamp.from(Instant.now().minus(Duration.ofDays(jours)));
  }

  private void creerCompte(
      final String id, final Timestamp derniereConnexion, final boolean invite) {
    jdbc.update(
        "INSERT INTO users (id, username, email, must_change_credentials, derniere_connexion_le)"
            + " VALUES (:id, :id, :email, :invite, :derniereConnexion)",
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("email", id + "@test.fr")
            .addValue("invite", invite)
            .addValue("derniereConnexion", derniereConnexion, Types.TIMESTAMP));
  }
}
