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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
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
  private List<SourceConcerts> sources;
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
    sources = List.of(tm, eventim);
    ResolveurNomCanonique resolveur = mock(ResolveurNomCanonique.class);
    persisterConcerts = mock(PersisterConcerts.class);
    recalculerMatchesService = mock(RecalculerMatchesService.class);
    gestionQuotaTM = mock(GestionQuotaTM.class);
    notificationDigestService = mock(NotificationDigestService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    workerService =
        new WorkerService(
            sources,
            tm,
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
    verify(tm, never()).fetchArtistesLot(any());
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

    workerService.executerFetchSalle("tm:venue-42");

    verify(tm, never()).fetchSalle(anyString());
    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_quotaDisponible_fetchEtPersiste() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    workerService.executerFetchSalle("tm:venue-42");

    verify(tm).fetchSalle("tm:venue-42");
    verify(persisterConcerts).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_identifiantsMultiples_dispatcheParSource() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-1")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-2")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-1")).thenReturn(List.of());
    when(tm.fetchSalle("tm:venue-2")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    workerService.executerFetchSalle("tm:venue-1,tm:venue-2");

    verify(tm).fetchSalle("tm:venue-1");
    verify(tm).fetchSalle("tm:venue-2");
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

  @Test
  void executerPurgerSalles_supprimeSallesOrphelinesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(3);

    workerService.executerPurgerSalles();

    verify(jdbc).update(contains("DELETE FROM salles"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerArtistes_supprimeArtistesOrphelinsEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(2);

    workerService.executerPurgerArtistes();

    verify(jdbc).update(contains("DELETE FROM artistes"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerLogsAdmin_supprimeTroisTablesEtEnregistreStat() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);

    workerService.executerPurgerLogsAdmin();

    verify(jdbc).update(contains("DELETE FROM logs_admin"), anyMap());
    verify(jdbc).update(contains("DELETE FROM fetch_jobs_historique"), anyMap());
    verify(jdbc).update(contains("DELETE FROM verifications"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs (type_job"), anyMap());
    verify(jdbc).update(contains("INSERT INTO fetch_jobs_historique"), anyMap());
  }

  @Test
  void executerPurgerLogsAdmin_erreur_nePropagePas() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> workerService.executerPurgerLogsAdmin());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerFetchSallesAll_quotaInsuffisant_neFetchPas() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(false);

    workerService.executerFetchSallesAll();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerFetchSallesAll_aucuneSalle_nePersiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

    workerService.executerFetchSallesAll();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executerFetchSallesAll_avecSalle_fetchEtPersiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("tm:venue-99"));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    workerService.executerFetchSallesAll();

    verify(tm).fetchSalle("tm:venue-99");
    verify(persisterConcerts).persisterConcerts(any());
  }

  @Test
  void executerRevoquerAdmins_aucunAdmin_nInsertePasLogs() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    workerService.executerRevoquerAdmins();

    verify(jdbc, times(1)).update(contains("UPDATE users"), anyMap());
    verify(jdbc, never()).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  @Test
  void executerRevoquerAdmins_avecAdmins_insereLogs() {
    when(jdbc.update(contains("UPDATE users"), anyMap())).thenReturn(2);
    when(jdbc.update(contains("INSERT INTO logs_admin"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs"), anyMap())).thenReturn(1);
    when(jdbc.update(contains("INSERT INTO fetch_jobs_historique"), anyMap())).thenReturn(1);

    workerService.executerRevoquerAdmins();

    verify(jdbc).update(contains("UPDATE users"), anyMap());
    verify(jdbc).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  @Test
  void executerRevoquerAdmins_erreur_nePropagePas() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> workerService.executerRevoquerAdmins());
  }
}
