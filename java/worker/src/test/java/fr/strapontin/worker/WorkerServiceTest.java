/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.sante.ReconciliationNocturneService;
import fr.strapontin.worker.sante.ResultatReconciliation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link WorkerService} — depuis, ne porte plus que le recalcul de matches et
/// la réconciliation nocturne (fetch, purge et révocation admin sont désormais dans leurs propres
/// services, testés séparément).
///
/// @since 2.2.3
/// @author Kwaite
class WorkerServiceTest {

  private RecalculerMatchesService recalculerMatchesService;
  private NotificationDigestService notificationDigestService;
  private ReconciliationNocturneService reconciliationNocturneService;
  private NamedParameterJdbcTemplate jdbc;
  private WorkerService workerService;

  @BeforeEach
  void setUp() {
    recalculerMatchesService = mock(RecalculerMatchesService.class);
    notificationDigestService = mock(NotificationDigestService.class);
    reconciliationNocturneService = mock(ReconciliationNocturneService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    TicketmasterSource tm = mock(TicketmasterSource.class);
    ResolveurNomCanonique resolveur = mock(ResolveurNomCanonique.class);
    LogsAdminWorker logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    WorkerStatsService workerStatsService =
        new WorkerStatsService(
            List.<SourceConcerts>of(),
            tm,
            resolveur,
            jdbc,
            logsAdminWorker,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new SuiviChroniciteService(jdbc, new ObjectMapper()),
            new DeriveVolumeFetchService(jdbc, logsAdminWorker));
    workerService =
        new WorkerService(
            recalculerMatchesService,
            notificationDigestService,
            reconciliationNocturneService,
            workerStatsService);
  }

  @Test
  void executerRecalculerMatches_nominal_appelleRecalculEtDigest() {
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(3);

    workerService.executerRecalculerMatches(List.of("user-1"));

    verify(recalculerMatchesService).recalculerMatches(List.of("user-1"));
    verify(notificationDigestService).executerDigest("temps_reel");
  }

  @Test
  void executerRecalculerMatches_erreur_nePropagePasEtEnregistreStat() {
    when(recalculerMatchesService.recalculerMatches(any())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> workerService.executerRecalculerMatches(List.of("user-1")));

    verify(jdbc, times(2)).update(anyString(), anyMap()); // fetch_jobs + fetch_jobs_historique
    verify(notificationDigestService, never()).executerDigest(anyString());
  }

  @Test
  void executerReconciliationNocturne_nominal_appelleLeServiceEtEnregistreLaStat() {
    when(reconciliationNocturneService.executer())
        .thenReturn(new ResultatReconciliation(2, 1, 0, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    workerService.executerReconciliationNocturne();

    verify(reconciliationNocturneService).executer();
    verify(jdbc).update(contains("INSERT INTO fetch_jobs "), anyMap());
  }

  @Test
  void executerReconciliationNocturne_exception_neRelancePasEtEnregistreErreur() {
    when(reconciliationNocturneService.executer()).thenThrow(new RuntimeException("Wikidata down"));

    assertDoesNotThrow(() -> workerService.executerReconciliationNocturne());

    verify(jdbc).update(contains("INSERT INTO fetch_jobs "), anyMap());
  }
}
