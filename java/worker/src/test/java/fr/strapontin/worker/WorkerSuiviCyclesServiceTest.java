/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link WorkerSuiviCyclesService}.
 *
 * @since 2.8.3
 * @author Kwaite
 */
class WorkerSuiviCyclesServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private WorkerSuiviCyclesService service;

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
    service = new WorkerSuiviCyclesService(jdbc, logsAdminWorker, workerStatsService);
  }

  private static Map<String, Object> ligne(final String typeJob, final Instant derniereExec) {
    final Map<String, Object> m = new HashMap<>();
    m.put("type_job", typeJob);
    m.put("derniere_exec", derniereExec != null ? Timestamp.from(derniereExec) : null);
    return m;
  }

  @Test
  void verifier_toutesLesFraicheursRespectees_retourneZeroEtNAlerteJamais() {
    final Instant maintenant = Instant.now();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(
            List.of(
                ligne("fetch:artistes:batch", maintenant.minus(1, ChronoUnit.HOURS)),
                ligne("revoquer:admins", maintenant.minus(30, ChronoUnit.MINUTES)),
                ligne("reconciliation:nocturne", maintenant.minus(6, ChronoUnit.HOURS))));

    assertEquals(0, service.verifier());
    verify(jdbc, never()).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  // Cas réel documenté (#1090) : un cycle fetch:artistes:batch perdu par un reboot brutal de
  // l'hôte doit être détectable après coup — un écart bien au-delà de la cadence normale (2-4h)
  // trahit un cycle silencieusement perdu, quelle qu'en soit la cause.
  @Test
  void verifier_jobBienAuDelaDeSaFraicheurAttendue_signaleEtRetourneUn() {
    final Instant maintenant = Instant.now();
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligne("fetch:artistes:batch", maintenant.minus(20, ChronoUnit.HOURS))));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    final int enRetard = service.verifier();

    assertEquals(1, enRetard);
    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> params) ->
                    ((String) params.get("message")).contains("fetch:artistes:batch")));
  }

  // Un déploiement récent n'a pas encore écrit de ligne fetch_jobs pour certains types de job —
  // absence de référence, jamais traité comme un retard.
  @Test
  void verifier_typeJobJamaisExecute_neSignalePas() {
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    assertEquals(0, service.verifier());
    verify(jdbc, never()).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  @Test
  void executerSuiviCycles_erreur_nePropagePas() {
    when(jdbc.queryForList(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> service.executerSuiviCycles());
  }
}
