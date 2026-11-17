/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.fetch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.ConcertBrut;
import fr.strapontin.sources.ResultatRegion;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.enrichissement.ResolveurNomCanonique;
import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.sources.ticketmaster.TicketmasterSource;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.maintenance.WorkerStatsService;
import fr.strapontin.worker.notification.NotificationDigestService;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.SignalementDatesFestivalEcartees;
import fr.strapontin.worker.sante.SuiviChroniciteService;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link WorkerFetchService} (extrait de {@code WorkerService}).
 *
 * <p>Les tests du bilan de panne (Eventim/Deezer/TM concerts manqués) sont dans {@code
 * WorkerStatsServiceTest}, testés directement au niveau où cette logique vit désormais.
 *
 * @since 2.2.3 (extrait dans sa forme actuelle en 2.7.0)
 * @author Kwaite
 */
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
  private LogsAdminWorker logsAdminWorker;
  private RetryLotsService retryLotsService;

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
    retryLotsService = mock(RetryLotsService.class);
    // Instance réelle de WorkerStatsService (adossée au jdbc mocké) : le bloc finally
    // enregistrerStatJob doit s'exécuter sans lever d'exception, mais son détail est testé dans
    // WorkerStatsServiceTest.
    logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    final WorkerStatsService workerStatsService =
        new WorkerStatsService(
            sources,
            tm,
            resolveur,
            jdbc,
            logsAdminWorker,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new SuiviChroniciteService(jdbc, new ObjectMapper()),
            new DeriveVolumeFetchService(jdbc, logsAdminWorker));
    final WorkerFetchCommun commun =
        new WorkerFetchCommun(
            jdbc, resolveur, logsAdminWorker, retryLotsService, tm, gestionQuotaTM);
    final FetchRapportage rapportage =
        new FetchRapportage(
            notificationDigestService,
            workerStatsService,
            suiviSanteSourcesService,
            new EnregistreurLieuxEventim(jdbc, eventim),
            mock(SignalementDatesFestivalEcartees.class));
    fetchService =
        new WorkerFetchService(
            sources, List.of(), persisterConcerts, recalculerMatchesService, rapportage, commun);
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
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      final Map<String, Object> row = new HashMap<>();
      row.put("artiste_id_str", "artiste-" + i);
      row.put("artiste_affiche", "Artiste " + i);
      row.put("attraction_id", "attractionId" + i);
      rows.add(row);
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
    verify(tm, times(2)).fetchArtistesLot(any());
  }

  // Un lot en échec doit rester visible même si le job dans son ensemble se termine sans
  // exception (les autres lots, absents ici, ne masquent donc rien).
  @Test
  void executerFetchArtistesBatch_lotEnEchec_remonteLogsAdminNiveauError() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-1");
    row.put("artiste_affiche", "Artiste 1");
    row.put("attraction_id", "attractionId1");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any()))
        .thenThrow(new RuntimeException("panne persistance"));

    fetchService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("fetch:artistes:batch")
                        && params.get("message").toString().contains("1/1 lot(s) échoué")));
  }

  // Le retry ciblé (RetryLotsService) doit recevoir les identifiants réels du lot en échec, pas
  // seulement un message texte — condition nécessaire pour ne retenter que les entités concernées.
  @Test
  void executerFetchArtistesBatch_lotEnEchec_transmetLesIdsAuRetryLotsService() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-1");
    row.put("artiste_affiche", "Artiste 1");
    row.put("attraction_id", "attractionId1");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any()))
        .thenThrow(new RuntimeException("panne persistance"));

    fetchService.executerFetchArtistesBatch();

    verify(retryLotsService).gererResultat("fetch:artistes:batch", List.of("artiste-1"));
  }

  // La surcharge ciblée ne doit interroger que les artistes passés, pas tous les artistes suivis.
  @Test
  void executerFetchArtistesBatch_avecIdsCibles_neFetchQueCesArtistes() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-1");
    row.put("artiste_affiche", "Artiste 1");
    row.put("attraction_id", "attractionId1");
    when(jdbc.queryForList(
            contains("artiste_id::text IN"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> List.of("artiste-1").equals(params.get("ids")))))
        .thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch(List.of("artiste-1"));

    verify(eventim).fetchArtiste("Artiste 1", null, null, List.of());
  }

  // Un cycle propre doit remettre à zéro le compteur de tentatives de retry — sans quoi il
  // resterait bloqué indéfiniment après la résolution d'un échec ponctuel.
  @Test
  void executerFetchArtistesBatch_cyclePropre_notifieRetryLotsServiceSansEchec() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-1");
    row.put("artiste_affiche", "Artiste 1");
    row.put("attraction_id", "attractionId1");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(retryLotsService).gererResultat("fetch:artistes:batch", List.of());
  }

  // Le compteur d'absences consécutives doit être mis à jour pour l'artiste effectivement
  // retraité dans le lot, permettant de détecter plus tard un concert disparu du catalogue.
  @Test
  void executerFetchArtistesBatch_lotTraite_metAJourLesAbsencesConsecutives() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-123");
    row.put("artiste_affiche", "Indila");
    row.put("attraction_id", "attractionId1");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("absences_consecutives"),
            org.mockito.ArgumentMatchers.argThat(
                (Map<String, Object> params) ->
                    ((List<?>) params.get("ids")).contains("artiste-123")));
  }

  // artiste_sources n'a pas de contrainte d'unicité sur artiste_id — deux identifiant_externe
  // ticketmaster pour le même artiste rendent le MAX() de la requête SQL arbitraire. Mieux vaut
  // ignorer le fetch TM pour cet artiste ce cycle que d'envoyer un identifiant potentiellement
  // faux, et flaguer necessite_intervention_le pour une résolution manuelle admin.
  @Test
  void executerFetchArtistesBatch_identifiantTmAmbigu_ignoreLeFetchEtFlagueIntervention() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-ambigu");
    row.put("artiste_affiche", "U2");
    row.put("attraction_id", "K8vZ91712W7");
    row.put("nb_ids_distincts", 2);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(tm, never()).fetchArtistesLot(any());
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("necessite_intervention_le"),
            eq(Map.of("artisteId", "artiste-ambigu")));
  }

  // deezer_id/wikidata_id doivent être transmis à fetchArtiste(nom, deezerId,
  // wikidataId) — condition nécessaire pour qu'EventimSource puisse désactiver le repli par
  // containment dès qu'une identité indépendante est déjà connue.
  @Test
  void executerFetchArtistesBatch_artisteAvecDeezerIdConnu_transmisAFetchArtiste() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "artiste-furies");
    row.put("artiste_affiche", "Furies");
    row.put("attraction_id", "attractionIdFuries");
    row.put("deezer_id", "656219");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenReturn(List.of());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(eventim).fetchArtiste("Furies", "656219", null, List.of());
  }

  @Test
  void executerFetchSalle_quotaIndisponible_neFetchPas() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(false);

    fetchService.executerFetchSalle("tm:venue-42");

    verify(tm, never()).fetchSalle(anyString(), any());
    verify(persisterConcerts, never()).persisterConcerts(any());
  }

  @Test
  void executerFetchSalle_quotaDisponible_fetchEtPersiste() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(tm).fetchSalle("tm:venue-42", null);
    verify(persisterConcerts).persisterConcerts(any());
  }

  // Deezer ne référence pas les humoristes — la résolution de nom canonique ne doit
  // jamais être appliquée à un concert classification="theatre" (ex. Deezer renverrait "Fabrice
  // Aboulker" pour la recherche "Fabrice Éboué").
  @Test
  void executerFetchSalle_concertClassificationTheatre_nAppliquePasLaResolutionDeezer() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    final ConcertBrut concertHumoriste =
        ConcertBrut.nouveau()
            .artiste("Fabrice Eboue")
            .date("2026-09-01")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://x")
            .source("ticketmaster")
            .classification("theatre")
            .construire();
    final ConcertBrut concertMusique =
        ConcertBrut.nouveau()
            .artiste("Indila")
            .date("2026-09-02")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://y")
            .source("ticketmaster")
            .classification("music")
            .construire();
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of(concertHumoriste, concertMusique));
    when(resolveur.resoudreNomCanonique("artiste", "Indila"))
        .thenReturn(
            new ResolveurNomCanonique.ResultatResolution(
                "Indila Officiel", ResolveurNomCanonique.SourceResolution.DEEZER));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(resolveur, never()).resoudreNomCanonique(eq("artiste"), eq("Fabrice Eboue"));
    verify(resolveur).resoudreNomCanonique("artiste", "Indila");
    final org.mockito.ArgumentCaptor<List<ConcertBrut>> captor =
        org.mockito.ArgumentCaptor.captor();
    verify(persisterConcerts).persisterConcerts(captor.capture());
    final List<ConcertBrut> persistes = captor.getValue();
    assertTrue(persistes.stream().anyMatch(c -> "Fabrice Eboue".equals(c.artiste())));
    assertTrue(persistes.stream().anyMatch(c -> "Indila Officiel".equals(c.artiste())));
  }

  // "divers" (spectacles non musicaux type Holiday On Ice) et "sports" ne correspondent
  // structurellement à aucun contenu musical référençable sur Deezer, au même titre que
  // "theatre" — la résolution ne doit pas s'y appliquer non plus.
  @Test
  void executerFetchSalle_concertClassificationDiversOuSports_nAppliquePasLaResolutionDeezer() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    final ConcertBrut concertDivers =
        ConcertBrut.nouveau()
            .artiste("Holiday On Ice")
            .date("2026-09-01")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://x")
            .source("ticketmaster")
            .classification("divers")
            .construire();
    final ConcertBrut concertSports =
        ConcertBrut.nouveau()
            .artiste("Equipe de France de basket-ball")
            .date("2026-09-02")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://y")
            .source("ticketmaster")
            .classification("sports")
            .construire();
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of(concertDivers, concertSports));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(resolveur, never()).resoudreNomCanonique(eq("artiste"), eq("Holiday On Ice"));
    verify(resolveur, never())
        .resoudreNomCanonique(eq("artiste"), eq("Equipe de France de basket-ball"));
  }

  // Eventim omet fréquemment sa classification y compris pour des spectacles non
  // musicaux (ex. "Paris is Magique", comédie musicale) — la résolution Deezer ne doit pas
  // s'appliquer à un concert Eventim sans classification.
  @Test
  void executerFetchSalle_concertEventimSansClassification_nAppliquePasLaResolutionDeezer() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(false);
    when(eventim.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    final ConcertBrut concertEventimSansClassification =
        ConcertBrut.nouveau()
            .artiste("Paris is Magique")
            .date("2026-09-01")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://x")
            .source("eventim")
            .construire();
    when(eventim.fetchSalle("tm:venue-42", null))
        .thenReturn(List.of(concertEventimSansClassification));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(resolveur, never()).resoudreNomCanonique(eq("artiste"), eq("Paris is Magique"));
  }

  // Un mauvais rapprochement Deezer (ex. "Nino Arial" -> "Niño Árbol") ne doit jamais l'emporter
  // sur un nom_affiche déjà connu et divergent, établi par une source plus fiable (Wikidata).
  @Test
  void executerFetchSalle_candidatDeezerDivergeDuNomAfficheWikidataConnu_gardeLeNomBrut() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    final ConcertBrut concert =
        ConcertBrut.nouveau()
            .artiste("Nino Arial")
            .date("2026-09-01")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://x")
            .source("ticketmaster")
            .classification("music")
            .construire();
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of(concert));
    when(resolveur.resoudreNomCanonique("artiste", "Nino Arial"))
        .thenReturn(
            new ResolveurNomCanonique.ResultatResolution(
                "Niño Árbol", ResolveurNomCanonique.SourceResolution.DEEZER));
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of("Nino Arial"));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    final org.mockito.ArgumentCaptor<List<ConcertBrut>> captor =
        org.mockito.ArgumentCaptor.captor();
    verify(persisterConcerts).persisterConcerts(captor.capture());
    assertTrue(captor.getValue().stream().allMatch(c -> "Nino Arial".equals(c.artiste())));
  }

  // Un nom_affiche connu cosmétiquement identique (casse/espace) au candidat Deezer ne doit pas
  // bloquer la résolution — seule une vraie divergence (via
  // CorrespondanceArtiste.estDoublonArtiste)
  // rejette le candidat.
  @Test
  void executerFetchSalle_candidatDeezerCoherentAvecNomAfficheWikidataConnu_accepteLeNomDeezer() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    final ConcertBrut concert =
        ConcertBrut.nouveau()
            .artiste("stromae")
            .date("2026-09-01")
            .heure("20:00")
            .salle("Salle")
            .ville("Paris")
            .dept("75")
            .url("http://x")
            .source("ticketmaster")
            .classification("music")
            .construire();
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of(concert));
    when(resolveur.resoudreNomCanonique("artiste", "stromae"))
        .thenReturn(
            new ResolveurNomCanonique.ResultatResolution(
                "Stromae", ResolveurNomCanonique.SourceResolution.DEEZER));
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of("STROMAE "));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    final org.mockito.ArgumentCaptor<List<ConcertBrut>> captor =
        org.mockito.ArgumentCaptor.captor();
    verify(persisterConcerts).persisterConcerts(captor.capture());
    assertTrue(captor.getValue().stream().allMatch(c -> "Stromae".equals(c.artiste())));
  }

  @Test
  void executerFetchSalle_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-42");

    verify(suiviSanteSourcesService).enregistrerResultatSalle("tm:venue-42", 0);
  }

  @Test
  void executerFetchSalle_identifiantsMultiples_dispatcheParSource() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-1")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-2")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-1", null)).thenReturn(List.of());
    when(tm.fetchSalle("tm:venue-2", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSalle("tm:venue-1,tm:venue-2");

    verify(tm).fetchSalle("tm:venue-1", null);
    verify(tm).fetchSalle("tm:venue-2", null);
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de départements
  // (taille 5), pas en un seul bloc final.
  @Test
  void executerFetchRegion_plusDeCinqDepartements_persisteParLotDeCinq() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final String[] depts = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String[]>>any()))
        .thenReturn(List.<String[]>of(depts));
    when(tm.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(eventim.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchRegion();

    verify(persisterConcerts, times(3)).persisterConcerts(any());
  }

  // Le bilan agrégé du job reste positif grâce aux lots réussis — l'échec ne doit pas s'y noyer.
  @Test
  void executerFetchRegion_unLotEnEchecParmiPlusieurs_remonteLogsAdminNiveauWarn() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final String[] depts = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String[]>>any()))
        .thenReturn(List.<String[]>of(depts));
    when(tm.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(eventim.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(persisterConcerts.persisterConcerts(any()))
        .thenReturn(new ResultatPersistance(1, 0))
        .thenThrow(new RuntimeException("panne réseau"))
        .thenReturn(new ResultatPersistance(1, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchRegion();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("1/3 lot(s) échoué")));
  }

  @Test
  void executerFetchRegion_unLotEnEchec_transmetLesDeptsAuRetryLotsService() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final String[] depts = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String[]>>any()))
        .thenReturn(List.<String[]>of(depts));
    when(tm.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(eventim.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(persisterConcerts.persisterConcerts(any()))
        .thenReturn(new ResultatPersistance(1, 0))
        .thenThrow(new RuntimeException("panne réseau"))
        .thenReturn(new ResultatPersistance(1, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchRegion();

    verify(retryLotsService).gererResultat("fetch:region", List.of("06", "07", "08", "09", "10"));
  }

  // La surcharge ciblée ne doit interroger que les départements passés, pas tous les départements
  // configurés — sinon le retry rejouerait le cycle complet plutôt qu'un simple sous-ensemble.
  @Test
  void executerFetchRegion_avecDeptsCibles_neFetchQueCesDepts() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(tm.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(eventim.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchRegion(List.of("75", "92"));

    verify(tm).fetchRegionCouverte(eq(List.of("75", "92")), any());
    verify(jdbc, never())
        .query(
            contains("user_config"),
            anyMap(),
            org.mockito.ArgumentMatchers.<RowMapper<String[]>>any());
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
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneSalle(UUID.randomUUID().toString(), "ticketmaster", "venue-99")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(tm).fetchSalle("tm:venue-99", null);
    verify(persisterConcerts).persisterConcerts(any());
  }

  // la requête SQL réelle agrège salle_sources.source ("ticketmaster", la valeur
  // canonique), jamais l'abréviation "tm" — sans traduction, TicketmasterSource.fetchSalle
  // n'est jamais invoqué (accepteIdentifiantSalle rejette silencieusement le préfixe long).
  @Test
  void executerFetchSallesAll_identifiantSourceTicketmaster_traduitEnPrefixeTmAvantFetch() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneSalle(UUID.randomUUID().toString(), "ticketmaster", "venue-99")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(tm).fetchSalle("tm:venue-99", null);
    verify(tm, never()).fetchSalle("ticketmaster:venue-99", null);
  }

  @Test
  void executerFetchSallesAll_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneSalle(UUID.randomUUID().toString(), "ticketmaster", "venue-99")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(suiviSanteSourcesService).enregistrerResultatSalle("tm:venue-99", 0);
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de salles (taille 20).
  @Test
  void executerFetchSallesAll_plusDeVingtSalles_persisteParLotDeVingt() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final List<Map<String, Object>> lignes = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      lignes.add(ligneSalle(UUID.randomUUID().toString(), "ticketmaster", "venue-" + i));
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(lignes);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle(anyString(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
  }

  // Même motif que fetch:region — un lot en échec parmi plusieurs réussis doit rester visible.
  @Test
  void executerFetchSallesAll_unLotEnEchecParmiPlusieurs_remonteLogsAdminNiveauWarn() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final List<Map<String, Object>> lignes = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      lignes.add(ligneSalle(UUID.randomUUID().toString(), "ticketmaster", "venue-" + i));
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(lignes);
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle(anyString(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any()))
        .thenThrow(new RuntimeException("panne persistance"))
        .thenReturn(new ResultatPersistance(1, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    fetchService.executerFetchSallesAll();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("1/2 lot(s) échoué")));
  }

  @Test
  void executerFetchSallesAll_lotEnEchec_transmetLesSalleIdsAuRetryLotsService() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap()))
        .thenReturn(List.of(ligneSalle("salle-1", "ticketmaster", "venue-1")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle(anyString(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any()))
        .thenThrow(new RuntimeException("panne persistance"));

    fetchService.executerFetchSallesAll();

    verify(retryLotsService).gererResultat("fetch:salles:all", List.of("salle-1"));
  }

  // La surcharge ciblée ne doit interroger que les salles passées, pas toutes les salles suivies.
  @Test
  void executerFetchSallesAll_avecSalleIdsCibles_neFetchQueCesSalles() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(
            contains("salle_id::text IN"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> List.of("salle-1").equals(params.get("ids")))))
        .thenReturn(List.of(ligneSalle("salle-1", "ticketmaster", "venue-1")));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle(anyString(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSallesAll(List.of("salle-1"));

    verify(tm).fetchSalle("tm:venue-1", null);
  }

  private static ResourceAccessException panneReseau() {
    return new ResourceAccessException(
        "I/O error on GET request", new IOException("Connection reset"));
  }

  // Une source injoignable ne vaut pas une salle vide : la salle en panne est retentée et
  // ses concerts ne comptent aucune absence, les autres salles du lot restent traitées.
  @Test
  void executerFetchSallesAll_panneReseauSurUneSalle_retenteEtNeCompteAucuneAbsenceSurElle() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> salle1 = ligneSalle("s1", "ticketmaster", "venue-1");
    final Map<String, Object> salle2 = ligneSalle("s2", "ticketmaster", "venue-2");
    when(jdbc.queryForList(contains("user_salles"), anyMap())).thenReturn(List.of(salle1, salle2));
    when(tm.accepteIdentifiantSalle(anyString())).thenReturn(true);
    when(tm.fetchSalle("tm:venue-1", null)).thenThrow(panneReseau());
    when(tm.fetchSalle("tm:venue-2", null)).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchSallesAll();

    verify(retryLotsService).gererResultat("fetch:salles:all", List.of("s1"));
    verify(suiviSanteSourcesService, never()).enregistrerResultatSalle(eq("tm:venue-1"), anyInt());
    verify(jdbc)
        .update(
            contains("absences_consecutives"),
            org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                params -> List.of("s2").equals(params.getValue("ids"))));
  }

  @Test
  void executerFetchRegion_panneReseauSurUneSource_retenteLeLotSansCompterDAbsence() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(tm.fetchRegionCouverte(any(), any())).thenThrow(panneReseau());
    when(eventim.fetchRegionCouverte(any(), any())).thenReturn(new ResultatRegion(List.of(), null));
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchRegion(List.of("75", "92"));

    verify(retryLotsService).gererResultat("fetch:region", List.of("75", "92"));
    verify(jdbc, never()).update(contains("absences_consecutives"), any(SqlParameterSource.class));
  }

  @Test
  void executerFetchArtistesBatch_panneReseauTM_neCompteAucuneAbsence() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    final Map<String, Object> row = new HashMap<>();
    row.put("artiste_id_str", "a1");
    row.put("artiste_affiche", "Jain");
    row.put("attraction_id", "K8vZ917oGV7");
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(row));
    when(tm.fetchArtistesLot(any())).thenThrow(panneReseau());
    when(eventim.fetchArtiste(anyString(), any(), any(), any())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    fetchService.executerFetchArtistesBatch();

    verify(retryLotsService).gererResultat("fetch:artistes:batch", List.of("a1"));
    verify(jdbc, never()).update(contains("absences_consecutives"), anyMap());
  }

  private static Map<String, Object> ligneSalle(
      final String salleId, final String source, final String identifiant) {
    final Map<String, Object> ligne = new HashMap<>();
    ligne.put("salle_id", salleId);
    ligne.put("source", source);
    ligne.put("identifiant", identifiant);
    ligne.put("libelle", null);
    return ligne;
  }
}
