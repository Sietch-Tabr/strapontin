/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.DigitickSource;
import fr.strapontin.sources.EventimSource;
import fr.strapontin.sources.ResolveurNomCanonique;
import fr.strapontin.sources.SourceConcerts;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.worker.persister.PersisterConcerts.ResultatPersistance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link WorkerStatsService} (extrait de {@code WorkerService}).
///
/// @since 2.7.0
/// @author Kwaite
class WorkerStatsServiceTest {

  private TicketmasterSource tm;
  private EventimSource eventim;
  private ResolveurNomCanonique resolveur;
  private NamedParameterJdbcTemplate jdbc;
  private LogsAdminWorker logsAdminWorker;
  private SimpleMeterRegistry meterRegistry;
  private DeriveVolumeFetchService deriveVolumeFetchService;
  private WorkerStatsService service;

  @BeforeEach
  void setUp() {
    tm = mock(TicketmasterSource.class);
    eventim = mock(EventimSource.class);
    when(tm.nom()).thenReturn("ticketmaster");
    when(eventim.nom()).thenReturn("eventim");
    resolveur = mock(ResolveurNomCanonique.class);
    jdbc = mock(NamedParameterJdbcTemplate.class);
    // Instance réelle adossée au jdbc mocké : les assertions verify(jdbc)... continuent de
    // fonctionner sans changer de cible.
    logsAdminWorker = new LogsAdminWorker(jdbc, new ObjectMapper());
    meterRegistry = new SimpleMeterRegistry();
    deriveVolumeFetchService = mock(DeriveVolumeFetchService.class);
    service = creerService(List.of(tm, eventim));
  }

  private WorkerStatsService creerService(List<SourceConcerts> sources) {
    return new WorkerStatsService(
        sources,
        tm,
        resolveur,
        jdbc,
        logsAdminWorker,
        meterRegistry,
        new SuiviChroniciteService(jdbc, new ObjectMapper()),
        deriveVolumeFetchService);
  }

  // bilan Eventim remonté via le mécanisme générique (instanceof SourceAvecBilanFetch),
  // pour tout job de type "fetch:".
  @Test
  void enregistrerStatJob_eventimPanneTotale_insereLogsAdminNiveauError() {
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 5));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void enregistrerStatJob_eventimEchecPartiel_insereLogsAdminNiveauWarn() {
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 2));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void enregistrerStatJob_eventimPanneTotaleSousLeSeuil_resteEnWarn() {
    // 100% d'échecs mais sous le seuil de 3 appels : pas d'alerte error, juste un warn.
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(2, 2));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Eventim")));
  }

  @Test
  void enregistrerStatJob_eventimAucunAppel_nInsereAucunLogEventim() {
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(0, 0));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("Eventim")));
  }

  // le bilan de panne n'est plus limité à Eventim — Digitick en bénéficie via la boucle
  // générique sur `sources` (instanceof SourceAvecBilanFetch), sans méthode dédiée.
  @Test
  void enregistrerStatJob_digitickPanneTotale_insereLogsAdminNiveauError() {
    DigitickSource digitick = mock(DigitickSource.class);
    when(digitick.nom()).thenReturn("digitick");
    when(digitick.preleverBilan()).thenReturn(new BilanFetch(4, 4));
    WorkerStatsService serviceAvecDigitick = creerService(List.of(tm, eventim, digitick));

    serviceAvecDigitick.enregistrerStatJob(
        "fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Digitick")));
  }

  // même bilan de panne étendu à Deezer (ResolveurNomCanonique, hors liste `sources` car
  // pas un SourceConcerts — remonté explicitement par WorkerStatsService).
  @Test
  void enregistrerStatJob_deezerPanneTotale_insereLogsAdminNiveauError() {
    when(resolveur.preleverBilan()).thenReturn(new BilanFetch(3, 3));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "error".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Deezer")));
  }

  // concertsManques reste une métrique TM spécifique, remontée séparément du bilan
  // appels/erreurs commun.
  @Test
  void enregistrerStatJob_tmConcertsManques_insereLogsAdminNiveauWarn() {
    when(tm.preleverConcertsManques()).thenReturn(7);

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("7 concert(s) TM")));
  }

  @Test
  void enregistrerStatJob_tmAucunConcertManque_nInsereAucunLog() {
    when(tm.preleverConcertsManques()).thenReturn(0);

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("concert(s) TM")));
  }

  // remonterResultatVide n'avait aucune assertion directe — jusque-là déclenché
  // silencieusement en marge d'autres tests (ex. bilan Eventim à zéro) sans jamais être vérifié.
  @Test
  void
      enregistrerStatJob_fetchResultatVideSansBilanNiErreur_insereLogsAdminNiveauWarnResultatVide() {
    service.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params
                            .get("message")
                            .toString()
                            .equals(
                                "fetch:region — 0 concert inséré ou mis à jour (résultat vide)")));
  }

  @Test
  void enregistrerStatJob_fetchAvecErreur_neRemontePasResultatVide() {
    service.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(0, 0), "timeout");

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("résultat vide")));
  }

  @Test
  void enregistrerStatJob_fetchReussi_appelleLaDeriveDeVolume() {
    service.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(3, 4), null);

    verify(deriveVolumeFetchService).verifier(eq("fetch:region"), eq(7), any());
  }

  @Test
  void enregistrerStatJob_fetchAvecErreur_nAppellePasLaDeriveDeVolume() {
    service.enregistrerStatJob("fetch:region", 10, new ResultatPersistance(3, 4), "timeout");

    verify(deriveVolumeFetchService, never()).verifier(anyString(), anyInt(), any());
  }

  // métrique Micrometer de durée par type de job, exposée via /actuator/prometheus.
  @Test
  void enregistrerStatJob_nominal_enregistreLaDureeDansLeTimerMicrometer() {
    service.enregistrerStatJob("fetch:region", 42, new ResultatPersistance(1, 0), null);

    var timer =
        meterRegistry
            .find("strapontin.worker.job.duration")
            .tag("type_job", "fetch:region")
            .timer();

    assertEquals(1, timer.count());
    assertEquals(42.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
  }

  // compteur d'erreurs par source, incrémenté à chaque bilan de panne comportant au
  // moins un échec (même sous le seuil d'escalade error/warn de remonterBilan).
  @Test
  void enregistrerStatJob_eventimAvecErreurs_incrementeLeCompteurMicrometer() {
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 2));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    var compteur =
        meterRegistry.find("strapontin.worker.source.erreurs").tag("source", "eventim").counter();

    assertEquals(2.0, compteur.count(), 0.001);
  }

  // les correspondances Eventim par containment (invisibles jusque-là, log SLF4J INFO
  // uniquement) sont remontées dans logs_admin au niveau warn à chaque cycle fetch.
  @Test
  void enregistrerStatJob_eventimContainmentsAcceptes_insereLogsAdminNiveauWarn() {
    when(eventim.preleverContainmentsAcceptes())
        .thenReturn(List.of("terme=Furies → resultat=Love & Furies"));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    "warn".equals(params.get("niveau"))
                        && params.get("message").toString().contains("Furies")));
  }

  @Test
  void enregistrerStatJob_eventimAucunContainment_nInsereAucunLogContainment() {
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc, never())
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params.get("message") != null
                        && params.get("message").toString().contains("containment")));
  }

  // le streak de chronicité (persisté dans app_config, cf. SuiviChroniciteService) doit survivre
  // entre deux appels à enregistrerStatJob — simulé ici par un stockage en mémoire derrière le
  // jdbc mocké, fidèle au comportement réel (INSERT ... ON CONFLICT DO UPDATE).
  private void simulerPersistanceAppConfig() {
    java.util.concurrent.atomic.AtomicReference<String> valeur =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(jdbc.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<java.util.Map<String, ?>>any(),
            org.mockito.ArgumentMatchers.eq(String.class)))
        .thenAnswer(_ -> valeur.get());
    when(jdbc.update(
            contains("INSERT INTO app_config"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, ?>>any()))
        .thenAnswer(
            invocation -> {
              java.util.Map<String, ?> params = invocation.getArgument(1);
              valeur.set((String) params.get("json"));
              return 1;
            });
  }

  @Test
  void enregistrerStatJob_echecPersistantSurTroisCycles_qualifieLeMessageDeChronique() {
    simulerPersistanceAppConfig();
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 2));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc)
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params ->
                    params
                        .get("message")
                        .toString()
                        .contains("chronique depuis 3 cycles consécutifs")));
  }

  @Test
  void enregistrerStatJob_cycleReussiApresDesEchecs_reinitialiseLeStreak() {
    simulerPersistanceAppConfig();
    when(eventim.preleverBilan())
        .thenReturn(new BilanFetch(5, 2))
        .thenReturn(new BilanFetch(5, 2))
        .thenReturn(new BilanFetch(5, 0))
        .thenReturn(new BilanFetch(5, 2));

    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);
    service.enregistrerStatJob("fetch:artistes:batch", 10, new ResultatPersistance(0, 0), null);

    verify(jdbc, never())
        .update(
            contains("INSERT INTO logs_admin"),
            org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(
                params -> params.get("message").toString().contains("chronique")));
  }

  @Test
  void enregistrerStatJob_typeJobHorsFetch_neRemontePasDeBilan() {
    // "purger:concerts" n'a pas le préfixe "fetch:" : la boucle de bilan de panne ne s'exécute
    // pas, seule l'insertion fetch_jobs/fetch_jobs_historique a lieu.
    when(eventim.preleverBilan()).thenReturn(new BilanFetch(5, 5));

    service.enregistrerStatJob("purger:concerts", 10, new ResultatPersistance(0, 3), null);

    verify(jdbc, never())
        .update(contains("INSERT INTO logs_admin"), org.mockito.ArgumentMatchers.anyMap());
    verify(jdbc)
        .update(
            contains("INSERT INTO fetch_jobs (type_job"), org.mockito.ArgumentMatchers.anyMap());
  }
}
