/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link WorkerFetchService} (extrait de {@code WorkerService}).
///
/// <p>Les tests du bilan de panne (Eventim/Digitick/Deezer/TM concerts manqués) sont dans
/// {@code WorkerStatsServiceTest}, testés directement au niveau où cette logique vit désormais.
///
/// @since 2.2.3 (extrait dans sa forme actuelle en 2.7.0)
/// @author Kwaite
class WorkerFetchServiceTest {

  private TicketmasterSource tm;
  private EventimSource eventim;
  private ResolveurNomCanonique resolveur;
  private List<SourceConcerts> sources;
  private PersisterConcerts persisterConcerts;
  private RecalculerMatchesService recalculerMatchesService;
  private GestionQuotaTM gestionQuotaTM;
  private NotificationDigestService notificationDigestService;
  private NamedParameterJdbcTemplate jdbc;
  private SuiviSanteSourcesService suiviSanteSourcesService;
  private WorkerFetchService fetchService;

  @BeforeEach
  void setUp() {
    tm = mock(TicketmasterSource.class);
    eventim = mock(EventimSource.class);
    when(tm.nom()).thenReturn("ticketmaster");
    when(eventim.nom()).thenReturn("eventim");
    sources = List.of(tm, eventim);
    resolveur = mock(ResolveurNomCanonique.class);
    persisterConcerts = mock(PersisterConcerts.class);
    recalculerMatchesService = mock(RecalculerMatchesService.class);
    gestionQuotaTM = mock(GestionQuotaTM.class);
    notificationDigestService = mock(NotificationDigestService.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    suiviSanteSourcesService = mock(SuiviSanteSourcesService.class);
    // Instance réelle de WorkerStatsService (adossée au jdbc mocké) : le bloc finally
    // enregistrerStatJob doit s'exécuter sans lever d'exception, mais son détail est testé dans
    // WorkerStatsServiceTest.
    LogsAdminWorker logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    WorkerStatsService workerStatsService =
        new WorkerStatsService(
            sources,
            tm,
            resolveur,
            jdbc,
            logsAdminWorker,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    fetchService =
        new WorkerFetchService(
            sources,
            tm,
            resolveur,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            jdbc,
            suiviSanteSourcesService,
            workerStatsService);
  }

  @Test
  void executerFetchArtistesBatch_quotaInsuffisant_neLancePasLeFetch() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(false);

    fetchService.executerFetchArtistesBatch();

    verify(persisterConcerts, never()).persisterConcerts(any());
    verify(tm, never()).fetchArtistesLot(any());
  }

  @Test
  void executerFetchArtistesBatch_aucunArtisteSuivi_neLancePasLaPersistance() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

    fetchService.executerFetchArtistesBatch();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  // Régression : persister uniquement à la toute fin perdrait tout le travail de fetch déjà
  // effectué (quota TM consommé) en cas d'interruption avant la fin du cycle — la persistance doit
  // avoir lieu lot par lot (taille 20), pas en un seul bloc final.
  @Test
  void executerFetchArtistesBatch_plusDeVingtCinqArtistes_persisteParLotDeVingt() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 25; i++) {
      java.util.Map<String, Object> row = new java.util.HashMap<>();
      row.put("artiste_id_str", "artiste-" + i);
      row.put("artiste_affiche", "Artiste " + i);
      row.put("attraction_id", "attractionId" + i);
      rows.add(row);
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
    verify(tm, times(2)).fetchArtistesLot(any());
  }

  // deezer_id/wikidata_id doivent être transmis à fetchArtiste(nom, deezerId,
  // wikidataId) — condition nécessaire pour qu'EventimSource puisse désactiver le repli par
  // containment dès qu'une identité indépendante est déjà connue.
  @Test
  void executerFetchArtistesBatch_artisteAvecDeezerIdConnu_transmisAFetchArtiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    java.util.Map<String, Object> row = new java.util.HashMap<>();
    row.put("artiste_id_str", "artiste-furies");
    row.put("artiste_affiche", "Furies");
    row.put("attraction_id", "attractionIdFuries");
    row.put("deezer_id", "656219");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(eventim).fetchArtiste("Furies", "656219", null);
  }

  @Test
  void executerFetchSalle_quotaIndisponible_neFetchPas() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(false);

    fetchService.executerFetchSalle("tm:venue-42");

    verify(tm, never()).fetchSalle(anyString());
    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_quotaDisponible_fetchEtPersiste() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(tm).fetchSalle("tm:venue-42");
    verify(persisterConcerts).persisterConcerts(any());
  }

  // Deezer ne référence pas les humoristes — la résolution de nom canonique ne doit
  // jamais être appliquée à un concert classification="theatre" (ex. Deezer renverrait "Fabrice
  // Aboulker" pour la recherche "Fabrice Éboué").
  @Test
  void executerFetchSalle_concertClassificationTheatre_nAppliquePasLaResolutionDeezer() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    fr.strapontin.core.ConcertBrut concertHumoriste =
        new fr.strapontin.core.ConcertBrut(
            "Fabrice Eboue",
            "2026-09-01",
            "20:00",
            "Salle",
            "Paris",
            "75",
            "http://x",
            "ticketmaster",
            "theatre",
            null,
            null,
            null,
            null,
            null,
            null);
    fr.strapontin.core.ConcertBrut concertMusique =
        new fr.strapontin.core.ConcertBrut(
            "Indila",
            "2026-09-02",
            "20:00",
            "Salle",
            "Paris",
            "75",
            "http://y",
            "ticketmaster",
            "music",
            null,
            null,
            null,
            null,
            null,
            null);
    when(tm.fetchSalle("tm:venue-42")).thenReturn(List.of(concertHumoriste, concertMusique));
    when(resolveur.resoudreNomCanonique("artiste", "Indila"))
        .thenReturn(
            new ResolveurNomCanonique.ResultatResolution(
                "Indila Officiel", ResolveurNomCanonique.SourceResolution.DEEZER));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(resolveur, never()).resoudreNomCanonique(eq("artiste"), eq("Fabrice Eboue"));
    verify(resolveur).resoudreNomCanonique("artiste", "Indila");
    org.mockito.ArgumentCaptor<List<fr.strapontin.core.ConcertBrut>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(persisterConcerts).persisterConcerts(captor.capture());
    List<fr.strapontin.core.ConcertBrut> persistes = captor.getValue();
    assertTrue(persistes.stream().anyMatch(c -> "Fabrice Eboue".equals(c.artiste())));
    assertTrue(persistes.stream().anyMatch(c -> "Indila Officiel".equals(c.artiste())));
  }

  @Test
  void executerFetchSalle_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(suiviSanteSourcesService).enregistrerResultatSalle("tm:venue-42", 0);
  }

  @Test
  void executerFetchSalle_identifiantsMultiples_dispatcheParSource() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-1")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-2")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-1")).thenReturn(List.of());
    when(tm.fetchSalle("tm:venue-2")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-1,tm:venue-2");

    verify(tm).fetchSalle("tm:venue-1");
    verify(tm).fetchSalle("tm:venue-2");
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de départements
  // (taille 5), pas en un seul bloc final.
  @Test
  void executerFetchRegion_plusDeCinqDepartements_persisteParLotDeCinq() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    String[] depts = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    when(jdbc.query(
            anyString(),
            anyMap(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String[]>>any()))
        .thenReturn(List.<String[]>of(depts));
    when(tm.fetchRegion(any(), any())).thenReturn(List.of());
    when(eventim.fetchRegion(any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchRegion();

    verify(persisterConcerts, times(3)).persisterConcerts(any());
  }

  @Test
  void executerFetchSallesAll_quotaInsuffisant_neFetchPas() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(false);

    fetchService.executerFetchSallesAll();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSallesAll_aucuneSalle_nePersiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

    fetchService.executerFetchSallesAll();

    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSallesAll_avecSalle_fetchEtPersiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("tm:venue-99"));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(tm).fetchSalle("tm:venue-99");
    verify(persisterConcerts).persisterConcerts(any());
  }

  // la requête SQL réelle agrège salle_sources.source ("ticketmaster", la valeur
  // canonique), jamais l'abréviation "tm" — sans traduction, TicketmasterSource.fetchSalle
  // n'est jamais invoqué (accepteIdentifiantSalle rejette silencieusement le préfixe long).
  @Test
  void executerFetchSallesAll_identifiantSourceTicketmaster_traduitEnPrefixeTmAvantFetch() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("ticketmaster:venue-99"));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(tm).fetchSalle("tm:venue-99");
    verify(tm, never()).fetchSalle("ticketmaster:venue-99");
  }

  @Test
  void executerFetchSallesAll_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("tm:venue-99"));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(suiviSanteSourcesService).enregistrerResultatSalle("tm:venue-99", 0);
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de salles (taille 20).
  @Test
  void executerFetchSallesAll_plusDeVingtSalles_persisteParLotDeVingt() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    java.util.List<String> identifiants = new java.util.ArrayList<>();
    for (int i = 0; i < 25; i++) identifiants.add("tm:venue-" + i);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(identifiants);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle(anyString())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
  }
}
