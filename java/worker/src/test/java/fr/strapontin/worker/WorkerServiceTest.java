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

import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link WorkerService} (orchestration des jobs, dépendances mockées).
///
/// @since 2.2.3
/// @author Kwaite
class WorkerServiceTest {

  private TicketmasterSource tm;
  private EventimSource eventim;
  private PersisterConcerts persisterConcerts;
  private RecalculerMatchesService recalculerMatchesService;
  private GestionQuotaTM gestionQuotaTM;
  private NotificationDigestService notificationDigestService;
  private NamedParameterJdbcTemplate jdbc;
  private WorkerService workerService;

  @BeforeEach
  void setUp() {
    tm = mock(TicketmasterSource.class);
    eventim = mock(EventimSource.class);
    ResolveurNomCanonique resolveur = mock(ResolveurNomCanonique.class);
    persisterConcerts = mock(PersisterConcerts.class);
    recalculerMatchesService = mock(RecalculerMatchesService.class);
    gestionQuotaTM = mock(GestionQuotaTM.class);
    notificationDigestService = mock(NotificationDigestService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    workerService =
        new WorkerService(
            tm,
            eventim,
            resolveur,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            jdbc);
  }

  @Test
  void executerFetchArtistesBatch_quotaInsuffisant_neLancePasLeFetch() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(false);

    workerService.executerFetchArtistesBatch();

    verify(persisterConcerts, never()).persisterConcerts(any());
    verify(tm, never()).fetchArtistesBatch(any());
  }

  @Test
  void executerFetchArtistesBatch_aucunArtisteSuivi_neLancePasLaPersistance() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    workerService.executerFetchArtistesBatch();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_quotaIndisponible_neFetchPas() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(false);

    workerService.executerFetchSalle("venue-42");

    verify(tm, never()).fetchSalle(anyString());
    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_quotaDisponible_fetchEtPersiste() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.fetchSalle("venue-42")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    workerService.executerFetchSalle("venue-42");

    verify(tm).fetchSalle("venue-42");
    verify(persisterConcerts).persisterConcerts(any());
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
  void executerPurgerConcerts_supprimeConcertsPassesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(5);

    workerService.executerPurgerConcerts();

    verify(jdbc).update(contains("DELETE FROM concerts"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }
}
