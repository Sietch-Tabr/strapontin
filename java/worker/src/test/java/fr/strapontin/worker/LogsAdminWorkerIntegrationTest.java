/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Écriture dans {@code logs_admin} ({@link LogsAdminWorker}) sur base réelle.
 *
 * @since 3.0.2
 * @author Kwaite
 */
class LogsAdminWorkerIntegrationTest extends AbstractWorkerIntegrationTest {

  @MockitoBean TicketmasterSource ticketmasterSource;

  @Autowired private NamedParameterJdbcTemplate jdbc;
  @Autowired private LogsAdminWorker logs;

  @BeforeEach
  void nettoyer() {
    jdbc.update("DELETE FROM logs_admin", Map.of());
  }

  private long lignes(final String niveau) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM logs_admin WHERE niveau = :niveau",
        Map.of("niveau", niveau),
        Long.class);
  }

  // « 1 correspondance(s) Eventim par containment » était réécrit à chaque cycle, 11 fois par jour.
  @Test
  void inserer_avertissementIdentiqueRepete_nEstEcritQuUneFois() {
    logs.inserer("warn", "worker", "correspondance Eventim par containment", null);
    logs.inserer("warn", "worker", "correspondance Eventim par containment", null);

    assertEquals(1, lignes("warn"));
  }

  @Test
  void inserer_avertissementsDifferents_sontTousEcrits() {
    logs.inserer("warn", "worker", "premier constat", null);
    logs.inserer("warn", "worker", "second constat", null);

    assertEquals(2, lignes("warn"));
  }

  // Les débuts et fins de job, identiques à chaque cycle, restent tous visibles.
  @Test
  void inserer_informationRepetee_estEcriteAChaqueFois() {
    logs.inserer("info", "worker", "Job démarré : purger:concerts", null);
    logs.inserer("info", "worker", "Job démarré : purger:concerts", null);

    assertEquals(2, lignes("info"));
  }
}
