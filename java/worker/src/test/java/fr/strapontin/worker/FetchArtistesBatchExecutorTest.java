/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SignalementDatesFestivalEcartees;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires de {@link FetchArtistesBatchExecutor} — remontée des
 * échecs réseau transitoires absorbés en profondeur (fetch TM par lot).
 *
 * @since 3.0.0
 * @author Kwaite
 */
class FetchArtistesBatchExecutorTest {

  private static Map<String, Object> ligneArtiste(final String id) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("artiste_id_str", id);
    ligne.put("artiste_affiche", "Jain");
    ligne.put("attraction_id", "K8vZ917oGV7");
    ligne.put("nb_ids_distincts", 1);
    ligne.put("deezer_id", null);
    ligne.put("wikidata_id", null);
    return ligne;
  }

  private static FetchArtistesBatchExecutor executeur(
      final NamedParameterJdbcTemplate jdbc,
      final TicketmasterSource tm,
      final WorkerFetchCommun commun) {
    when(commun.obtenirJdbc()).thenReturn(jdbc);
    when(commun.obtenirTm()).thenReturn(tm);
    when(commun.obtenirGestionQuotaTM()).thenReturn(mock(GestionQuotaTM.class));
    when(commun.obtenirGestionQuotaTM().cronAutorise()).thenReturn(true);
    when(commun.resoudreEtDeduplicer(anyList(), any())).thenReturn(List.of());

    final PersisterConcerts persisterConcerts = mock(PersisterConcerts.class);
    when(persisterConcerts.persisterConcerts(anyList())).thenReturn(new ResultatPersistance(0, 0));

    final WorkerStatsService workerStatsService = mock(WorkerStatsService.class);
    doAnswer(
            invocation -> {
              final Supplier<WorkerStatsService.ResultatJob> travail = invocation.getArgument(3);
              travail.get();
              return null;
            })
        .when(workerStatsService)
        .executerAvecStatsToleretErreur(any(), anyString(), anyBoolean(), any());
    final FetchRapportage rapportage =
        new FetchRapportage(
            mock(NotificationDigestService.class),
            workerStatsService,
            mock(SuiviSanteSourcesService.class),
            mock(EnregistreurLieuxEventim.class),
            mock(SignalementDatesFestivalEcartees.class));

    return new FetchArtistesBatchExecutor(
        List.of((SourceConcerts) () -> "aucune-source-simple"),
        persisterConcerts,
        mock(RecalculerMatchesService.class),
        rapportage,
        commun);
  }

  // #1247 : une panne réseau (DNS, I/O) absorbée en profondeur par fetchParLotsAttractionIds ne
  // doit plus être un échec silencieux — remonterEchecsLots doit recevoir le lot en échec pour
  // que RetryLotsService puisse effectivement se déclencher.
  @Test
  void executer_pannedReseauSurFetchTM_remonteLeLotEnEchec() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneArtiste("a1")));
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(tm.fetchArtistesLot(anyList()))
        .thenThrow(new RuntimeException(new UnknownHostException("app.ticketmaster.com")));
    final WorkerFetchCommun commun = mock(WorkerFetchCommun.class);
    final FetchArtistesBatchExecutor executeur = executeur(jdbc, tm, commun);

    executeur.executer(null);

    verify(commun)
        .remonterEchecsLots(
            eq("fetch:artistes:batch"),
            org.mockito.ArgumentMatchers.argThat(echecs -> !echecs.isEmpty()),
            eq(1),
            eq(List.of("a1")));
  }

  // Contre-épreuve : un échec applicatif (pas une IOException, ex. réponse HTTP mappée en
  // exception métier) ne doit pas déclencher le retry réseau — seul un vrai problème réseau
  // justifie la remontée à RetryLotsService.
  @Test
  void executer_echecApplicatifSurFetchTM_neRemontePasLeLotEnEchec() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneArtiste("a1")));
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(tm.fetchArtistesLot(anyList())).thenThrow(new IllegalStateException("réponse invalide"));
    final WorkerFetchCommun commun = mock(WorkerFetchCommun.class);
    final FetchArtistesBatchExecutor executeur = executeur(jdbc, tm, commun);

    executeur.executer(null);

    verify(commun)
        .remonterEchecsLots(eq("fetch:artistes:batch"), eq(List.of()), eq(1), eq(List.of()));
  }

  @Test
  void executer_succesSansPanne_neRemonteAucunEchec() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneArtiste("a1")));
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(tm.fetchArtistesLot(anyList())).thenReturn(List.<ConcertBrut>of());
    final WorkerFetchCommun commun = mock(WorkerFetchCommun.class);
    final FetchArtistesBatchExecutor executeur = executeur(jdbc, tm, commun);

    executeur.executer(null);

    verify(commun, never())
        .remonterEchecsLots(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(echecs -> !echecs.isEmpty()),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.argThat(ids -> !ids.isEmpty()));
  }

  private static FetchUnArtisteExecutor unArtiste(
      final FetchArtistesBatchExecutor executeur, final WorkerFetchCommun commun) {
    return new FetchUnArtisteExecutor(
        executeur,
        mock(RecalculerMatchesService.class),
        new FetchRapportage(
            mock(NotificationDigestService.class),
            mock(WorkerStatsService.class),
            mock(SuiviSanteSourcesService.class),
            mock(EnregistreurLieuxEventim.class),
            mock(SignalementDatesFestivalEcartees.class)),
        commun);
  }

  // L'ajout d'un artiste ne récupère que lui : ni cycle complet, ni remontée d'échec de lot.
  @Test
  void fetchUnArtiste_recupereLeSeulArtisteSansBilanDeCycle() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneArtiste("a1")));
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    when(tm.fetchArtistesLot(anyList())).thenReturn(List.<ConcertBrut>of());
    final WorkerFetchCommun commun = mock(WorkerFetchCommun.class);
    final FetchArtistesBatchExecutor executeur = executeur(jdbc, tm, commun);
    when(commun.obtenirGestionQuotaTM().verifier("fetch:artiste")).thenReturn(true);

    unArtiste(executeur, commun).executer("a1");

    verify(jdbc)
        .queryForList(
            org.mockito.ArgumentMatchers.contains("IN (:ids)"), eq(Map.of("ids", List.of("a1"))));
    verify(tm).fetchArtistesLot(List.of("K8vZ917oGV7"));
    verify(commun, never())
        .remonterEchecsLots(
            anyString(), anyList(), org.mockito.ArgumentMatchers.anyInt(), anyList());
  }

  @Test
  void fetchUnArtiste_quotaTicketmasterEpuise_nInterrogePasTicketmaster() throws Exception {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(ligneArtiste("a1")));
    final TicketmasterSource tm = mock(TicketmasterSource.class);
    final WorkerFetchCommun commun = mock(WorkerFetchCommun.class);
    final FetchArtistesBatchExecutor executeur = executeur(jdbc, tm, commun);
    when(commun.obtenirGestionQuotaTM().verifier("fetch:artiste")).thenReturn(false);

    unArtiste(executeur, commun).executer("a1");

    verify(tm, never()).fetchArtistesLot(anyList());
  }
}
