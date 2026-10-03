/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link WorkerRevocationService} (extrait de {@code WorkerService}).
 *
 * @since 2.2.6 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
class WorkerRevocationServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private WorkerRevocationService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final ResolveurNomCanonique resolveur = mock(ResolveurNomCanonique.class);
    final LogsAdminWorker logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    final WorkerStatsService workerStatsService =
        new WorkerStatsService(
            List.<SourceConcerts>of(),
            tm,
            resolveur,
            jdbc,
            logsAdminWorker,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new SuiviChroniciteService(jdbc, new ObjectMapper()),
            new DeriveVolumeFetchService(jdbc, logsAdminWorker));
    service = new WorkerRevocationService(jdbc, workerStatsService);
  }

  @Test
  void executerRevoquerAdmins_aucunAdmin_nInsertePasLogs() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    service.executerRevoquerAdmins();

    verify(jdbc, times(1)).update(contains("UPDATE users"), anyMap());
    verify(jdbc, never()).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  @Test
  void executerRevoquerAdmins_avecAdmins_insereLogs() {
    when(jdbc.queryForList(contains("SELECT username"), anyMap(), eq(String.class)))
        .thenReturn(List.of("ex-admin1", "ex-admin2"));
    when(jdbc.update(contains("UPDATE users"), anyMap())).thenReturn(2);
    when(jdbc.update(contains("DELETE FROM SPRING_SESSION"), anyMap())).thenReturn(3);
    when(jdbc.update(contains("INSERT INTO logs_admin"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs_historique"), anyMap())).thenReturn(1);

    service.executerRevoquerAdmins();

    verify(jdbc).update(contains("UPDATE users"), anyMap());
    verify(jdbc).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  // après rétrogradation effective, les sessions Spring Session des comptes concernés
  // sont supprimées — sinon un admin rétrogradé reste authentifié avec son ancien rôle jusqu'à
  // expiration naturelle de sa session (30 jours).
  @Test
  void executerRevoquerAdmins_avecAdmins_revoqueLesSessions() {
    when(jdbc.queryForList(contains("SELECT username"), anyMap(), eq(String.class)))
        .thenReturn(List.of("ex-admin1", "ex-admin2"));
    when(jdbc.update(contains("UPDATE users"), anyMap())).thenReturn(2);
    when(jdbc.update(contains("DELETE FROM SPRING_SESSION"), anyMap())).thenReturn(3);
    when(jdbc.update(contains("INSERT INTO logs_admin"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs_historique"), anyMap())).thenReturn(1);

    service.executerRevoquerAdmins();

    verify(jdbc)
        .update(
            contains("DELETE FROM SPRING_SESSION"),
            eq(Map.of("usernames", List.of("ex-admin1", "ex-admin2"))));
  }

  @Test
  void executerRevoquerAdmins_aucunAdminRevoque_neSupprimeAucuneSession() {
    when(jdbc.queryForList(contains("SELECT username"), anyMap(), eq(String.class)))
        .thenReturn(List.of());
    when(jdbc.update(contains("UPDATE users"), anyMap())).thenReturn(0);

    service.executerRevoquerAdmins();

    verify(jdbc, never()).update(contains("DELETE FROM SPRING_SESSION"), anyMap());
  }

  @Test
  void executerRevoquerAdmins_erreur_nePropagePas() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> service.executerRevoquerAdmins());
  }
}
