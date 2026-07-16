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

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.DigitickSource;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import fr.strapontin.worker.persister.RecalculerMatchesService;
import fr.strapontin.worker.quota.GestionQuotaTM;
import fr.strapontin.worker.sante.ReconciliationNocturneService;
import fr.strapontin.worker.sante.ResultatReconciliation;
import fr.strapontin.worker.sante.SuiviSanteSourcesService;
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
  private ResolveurNomCanonique resolveur;
  private List<SourceConcerts> sources;
  private PersisterConcerts persisterConcerts;
  private RecalculerMatchesService recalculerMatchesService;
  private GestionQuotaTM gestionQuotaTM;
  private NotificationDigestService notificationDigestService;
  private NamedParameterJdbcTemplate jdbc;
  private SuiviSanteSourcesService suiviSanteSourcesService;
  private ReconciliationNocturneService reconciliationNocturneService;
  private LogsAdminWorker logsAdminWorker;
  private WorkerService workerService;

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
    reconciliationNocturneService = mock(ReconciliationNocturneService.class);
    // Instance réelle adossée au jdbc mocké : les assertions verify(jdbc)... existantes
    // continuent de fonctionner sans changer de cible (#909).
    logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    workerService =
        new WorkerService(
            sources,
            tm,
            eventim,
            resolveur,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            jdbc,
            suiviSanteSourcesService,
            reconciliationNocturneService,
            logsAdminWorker);
  }

  // #901 : bilan Eventim remonté via remonterBilanEventim (appelé depuis enregistrerStatJob à
  // chaque job "fetch:"). executerFetchArtistesBatch (chemin "aucun artiste suivi") est le plus
  // simple à exercer : le finally d'enregistrerStatJob s'exécute même sur ce retour anticipé.
  private void stuberFetchArtistesBatchMinimal() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
  }

  @Test
  void executerFetchArtistesBatch_eventimPanneTotale_insereLogsAdminNiveauError() {
    stuberFetchArtistesBatchMinimal();
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 5));

    workerService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void executerFetchArtistesBatch_eventimEchecPartiel_insereLogsAdminNiveauWarn() {
    stuberFetchArtistesBatchMinimal();
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 2));

    workerService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void executerFetchArtistesBatch_eventimPanneTotaleSousLeSeuil_resteEnWarn() {
    // 100% d'échecs mais sous le seuil de 3 appels : pas d'alerte error, juste un warn.
    stuberFetchArtistesBatchMinimal();
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(2, 2));

    workerService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void executerFetchArtistesBatch_eventimAucunAppel_nInsereAucunLogEventim() {
    stuberFetchArtistesBatchMinimal();
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(0, 0));

    workerService.executerFetchArtistesBatch();

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("Eventim")));
  }

  // #909 : le bilan de panne n'est plus limité à Eventim — Digitick en bénéficie via la boucle
  // générique sur `sources` (instanceof SourceAvecBilanFetch), sans méthode dédiée.
  @Test
  void executerFetchArtistesBatch_digitickPanneTotale_insereLogsAdminNiveauError() {
    DigitickSource digitick = mock(DigitickSource.class);
    when(digitick.nom()).thenReturn("digitick");
    WorkerService service =
        new WorkerService(
            List.of(tm, eventim, digitick),
            tm,
            eventim,
            resolveur,
            persisterConcerts,
            recalculerMatchesService,
            gestionQuotaTM,
            notificationDigestService,
            jdbc,
            suiviSanteSourcesService,
            reconciliationNocturneService,
            logsAdminWorker);
    stuberFetchArtistesBatchMinimal();
    when(digitick.preleverBilan()).thenReturn(new BilanFetch(4, 4));

    service.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Digitick")));
  }

  // #912 : même bilan de panne étendu à Deezer (ResolveurNomCanonique, hors liste `sources` car
  // pas un SourceConcerts — remonté explicitement par WorkerService).
  @Test
  void executerFetchArtistesBatch_deezerPanneTotale_insereLogsAdminNiveauError() {
    stuberFetchArtistesBatchMinimal();
    when(resolveur.preleverBilan()).thenReturn(new BilanFetch(3, 3));

    workerService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Deezer")));
  }

  // #909 : concertsManques reste une métrique TM spécifique, remontée séparément du bilan
  // appels/erreurs commun.
  @Test
  void executerFetchArtistesBatch_tmConcertsManques_insereLogsAdminNiveauWarn() {
    stuberFetchArtistesBatchMinimal();
    when(tm.preleverConcertsManques()).thenReturn(7);

    workerService.executerFetchArtistesBatch();

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("7 concert(s) TM")));
  }

  @Test
  void executerFetchArtistesBatch_tmAucunConcertManque_nInsereAucunLog() {
    stuberFetchArtistesBatchMinimal();
    when(tm.preleverConcertsManques()).thenReturn(0);

    workerService.executerFetchArtistesBatch();

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("concert(s) TM")));
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
    when(eventim.fetchArtiste(anyString())).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    workerService.executerFetchArtistesBatch();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
    verify(tm, times(2)).fetchArtistesLot(any());
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
  void executerFetchSalle_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.verifier("fetch:salle")).thenReturn(true);
    when(tm.accepteIdentifiantSalle("tm:venue-42")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-42")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));

    workerService.executerFetchSalle("tm:venue-42");

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

    workerService.executerFetchSalle("tm:venue-1,tm:venue-2");

    verify(tm).fetchSalle("tm:venue-1");
    verify(tm).fetchSalle("tm:venue-2");
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de départements
  // (taille 5), pas en un seul bloc final.
  @Test
  @SuppressWarnings("unchecked")
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

    workerService.executerFetchRegion();

    verify(persisterConcerts, times(3)).persisterConcerts(any());
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
  @SuppressWarnings("unchecked")
  void executerFetchSallesAll_appelleSuiviSanteSourcesAvecLeResultat() {
    when(gestionQuotaTM.cronAutorise()).thenReturn(true);
    when(jdbc.queryForList(anyString(), anyMap(), eq(String.class)))
        .thenReturn(List.of("tm:venue-99"));
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    when(tm.accepteIdentifiantSalle("tm:venue-99")).thenReturn(true);
    when(tm.fetchSalle("tm:venue-99")).thenReturn(List.of());
    when(persisterConcerts.persisterConcerts(any())).thenReturn(new ResultatPersistance(0, 0));
    when(recalculerMatchesService.recalculerMatches(any())).thenReturn(0);

    workerService.executerFetchSallesAll();

    verify(suiviSanteSourcesService).enregistrerResultatSalle("tm:venue-99", 0);
  }

  // Régression : même motif que fetch:artistes:batch — persistance par lot de salles (taille 20).
  @Test
  @SuppressWarnings("unchecked")
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

    workerService.executerFetchSallesAll();

    verify(persisterConcerts, times(2)).persisterConcerts(any());
  }

  @Test
  void executerReconciliationNocturne_nominal_appelleLeServiceEtEnregistreLaStat() {
    when(reconciliationNocturneService.executer())
        .thenReturn(new ResultatReconciliation(2, 1, 0, 3, 4));

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

  @Test
  void executerRevoquerAdmins_aucunAdmin_nInsertePasLogs() {
    when(jdbc.update(anyString(), anyMap())).thenReturn(0);

    workerService.executerRevoquerAdmins();

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

    workerService.executerRevoquerAdmins();

    verify(jdbc).update(contains("UPDATE users"), anyMap());
    verify(jdbc).update(contains("INSERT INTO logs_admin"), anyMap());
  }

  // #809 : après rétrogradation effective, les sessions Spring Session des comptes concernés
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

    workerService.executerRevoquerAdmins();

    verify(jdbc)
        .update(
            contains("DELETE FROM SPRING_SESSION"),
            eq(java.util.Map.of("usernames", List.of("ex-admin1", "ex-admin2"))));
  }

  @Test
  void executerRevoquerAdmins_aucunAdminRevoque_neSupprimeAucuneSession() {
    when(jdbc.queryForList(contains("SELECT username"), anyMap(), eq(String.class)))
        .thenReturn(List.of());
    when(jdbc.update(contains("UPDATE users"), anyMap())).thenReturn(0);

    workerService.executerRevoquerAdmins();

    verify(jdbc, never()).update(contains("DELETE FROM SPRING_SESSION"), anyMap());
  }

  @Test
  void executerRevoquerAdmins_erreur_nePropagePas() {
    when(jdbc.update(anyString(), anyMap())).thenThrow(new RuntimeException("DB down"));

    assertDoesNotThrow(() -> workerService.executerRevoquerAdmins());
  }
}
