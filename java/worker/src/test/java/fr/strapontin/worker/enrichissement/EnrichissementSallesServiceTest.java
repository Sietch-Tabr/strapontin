/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.strapontin.core.BilanFetch;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.SourceAvecBilanFetch;
import fr.strapontin.sources.SourceEnrichissementSalle;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.SuiviChroniciteService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link EnrichissementSallesService} (dépendances mockées).
///
/// @since 2.6.0
/// @author Kwaite
class EnrichissementSallesServiceTest {

  @SuppressWarnings("unchecked")
  private static NamedParameterJdbcTemplate jdbcAvecCandidats(List<SalleAEnrichir> candidats) {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(candidats);
    return jdbc;
  }

  private static LogsAdminWorker logsAdminWorkerMock() {
    return mock(LogsAdminWorker.class);
  }

  // instance réelle adossée au jdbc mocké du test (comportement passthrough par défaut, aucun
  // streak persisté ne survit entre deux tests indépendants) — cf. WorkerStatsServiceTest.
  private static SuiviChroniciteService suiviChronicite(NamedParameterJdbcTemplate jdbc) {
    return new SuiviChroniciteService(jdbc, new ObjectMapper());
  }

  @Test
  void enrichir_aucunCandidat_retourneZeroSansAppelerLesSources() {
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of());
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(0, nb);
    verify(source, never()).enrichirLot(any());
  }

  // Un bilan d'appels/erreurs à 0 échec ne détecte pas une source qui répond systématiquement
  // "200 OK, aucun résultat" (ex. panne géographique silencieuse d'Overpass) — un cycle avec un
  // nombre significatif de candidats et 0 salle enrichie doit être journalisé séparément.
  @Test
  void enrichir_vingtCandidatsZeroEnrichis_journaliseUneAlerte() {
    List<SalleAEnrichir> candidats = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      candidats.add(
          new SalleAEnrichir(UUID.randomUUID(), "Salle " + i, "salle " + i, null, null, null));
    }
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any())).thenReturn(Map.of());
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(0, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_moinsDeVingtCandidatsZeroEnrichis_neJournalisePasDAlerte() {
    SalleAEnrichir candidat =
        new SalleAEnrichir(UUID.randomUUID(), "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any())).thenReturn(Map.of());
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    service.enrichir();

    verify(logsAdminWorker, never())
        .inserer(anyString(), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  // Un seuil strict à 0 salle enrichie ne détectait qu'une panne totale — une dégradation
  // chronique mais non totale (quelques succès isolés sur un gros lot) doit désormais aussi
  // déclencher l'alerte via un seuil de taux de réussite.
  @Test
  void enrichir_trenteCandidatsTroisEnrichisTauxSousLeSeuil_journaliseUneAlerte() {
    List<SalleAEnrichir> candidats = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      candidats.add(new SalleAEnrichir(id, "Salle " + i, "salle " + i, null, null, null));
    }
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    // 3 succès sur 30 (10%), répartis sur les deux premiers lots de persistance (25 puis 5).
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              List<SalleAEnrichir> lot = invocation.getArgument(0);
              Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) {
                if (ids.indexOf(s.id()) < 3) {
                  resultat.put(
                      s.id(), new EnrichissementSalle("Q" + s.id(), null, null, null, null));
                }
              }
              return resultat;
            });
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(3, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_vingtCandidatsDixEnrichisBonTaux_neJournalisePasDAlerte() {
    List<SalleAEnrichir> candidats = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      candidats.add(new SalleAEnrichir(id, "Salle " + i, "salle " + i, null, null, null));
    }
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    // 10 succès sur 20 (50%, largement au-dessus du seuil).
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              List<SalleAEnrichir> lot = invocation.getArgument(0);
              Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) {
                if (ids.indexOf(s.id()) < 10) {
                  resultat.put(
                      s.id(), new EnrichissementSalle("Q" + s.id(), null, null, null, null));
                }
              }
              return resultat;
            });
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(10, nb);
    verify(logsAdminWorker, never())
        .inserer(anyString(), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_overpassTrouveWikidataId_wikidataUtiliseLeQidPropage() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    SourceEnrichissementSalle overpass = mock(SourceEnrichissementSalle.class);
    when(overpass.enrichirLot(List.of(candidat)))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q123", null, null, null, null)));

    SourceEnrichissementSalle wikidata = mock(SourceEnrichissementSalle.class);
    SalleAEnrichir candidatAvecQid =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, "Q123");
    when(wikidata.enrichirLot(List.of(candidatAvecQid)))
        .thenReturn(
            Map.of(salleId, new EnrichissementSalle("Q123", "Zénith", "Nantes", null, null)));

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(overpass, wikidata),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(1, nb);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    "Q123".equals(params.get("wikidataId"))
                        && "Zénith".equals(params.get("nomAffiche"))
                        && "Nantes".equals(params.get("ville"))));
  }

  @Test
  void enrichir_troisiemeSourceMockeeAjoutee_prisEnCompteSansChangementDeCode() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Salle X", "salle x", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    SourceEnrichissementSalle source1 = mock(SourceEnrichissementSalle.class);
    when(source1.enrichirLot(any())).thenReturn(Map.of());
    SourceEnrichissementSalle source2 = mock(SourceEnrichissementSalle.class);
    when(source2.enrichirLot(any())).thenReturn(Map.of());
    SourceEnrichissementSalle source3 =
        mock(SourceEnrichissementSalle.class); // "3e" source ajoutée sans modifier le service
    when(source3.enrichirLot(any()))
        .thenReturn(
            Map.of(salleId, new EnrichissementSalle(null, "Salle X Canonique", null, null, null)));

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source1, source2, source3),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(1, nb);
    verify(source3, times(1)).enrichirLot(any());
  }

  @Test
  void enrichir_nomAfficheDejaFourniParUneSource_neSecrasePasParSuivante() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    SourceEnrichissementSalle source1 = mock(SourceEnrichissementSalle.class);
    when(source1.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", "Nom Un", null, null, null)));
    SourceEnrichissementSalle source2 = mock(SourceEnrichissementSalle.class);
    when(source2.enrichirLot(any())).thenReturn(Map.of());

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source1, source2),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> "Nom Un".equals(params.get("nomAffiche"))));
  }

  // Régression : persister uniquement à la toute fin perdrait tout le travail déjà résolu en cas
  // d'interruption (redéploiement, crash) avant la fin du cycle — la persistance doit avoir lieu
  // lot par lot, pas en un seul bloc final après appel de toutes les sources.
  @Test
  void enrichir_plusDeCandidatsQueLaTailleDeLot_persisteParLotAvantLeLotSuivant() {
    List<SalleAEnrichir> candidats = new ArrayList<>();
    Map<UUID, EnrichissementSalle> reponseAttendue = new HashMap<>();
    for (int i = 0; i < 30; i++) {
      UUID id = UUID.randomUUID();
      candidats.add(new SalleAEnrichir(id, "Salle" + i, "salle" + i, null, null, null));
      reponseAttendue.put(id, new EnrichissementSalle("Q" + i, null, null, null, null));
    }
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);

    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              List<SalleAEnrichir> lot = invocation.getArgument(0);
              Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) resultat.put(s.id(), reponseAttendue.get(s.id()));
              return resultat;
            });

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(30, nb);
    verify(source, times(2)).enrichirLot(any());
    InOrder ordre = inOrder(source, jdbc);
    ordre.verify(source).enrichirLot(any());
    ordre.verify(jdbc, times(25)).update(anyString(), anyMap());
    ordre.verify(source).enrichirLot(any());
    ordre.verify(jdbc, times(5)).update(anyString(), anyMap());
  }

  // Régression prod (2 juillet 2026, cycle 06h25) : deux salles distinctes matchées sur le même
  // wikidataId ont fait remonter une DuplicateKeyException (idx_salles_wikidata_id) qui a tué tout
  // le cycle de réconciliation nocturne avant le traitement des lots suivants. Le conflit doit être
  // isolé à la salle concernée, loggé, et ne jamais interrompre le reste du lot ni les lots
  // suivants.
  @Test
  void enrichir_wikidataIdDejaAttribueAUneAutreSalle_loggeLeConflitEtContinueLesAutresSalles() {
    UUID salleEnConflit = UUID.randomUUID();
    UUID salleOk = UUID.randomUUID();
    SalleAEnrichir candidatConflit =
        new SalleAEnrichir(salleEnConflit, "Orleans Arena", "orleans arena", null, null, null);
    SalleAEnrichir candidatOk = new SalleAEnrichir(salleOk, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidatConflit, candidatOk));
    when(jdbc.update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> salleEnConflit.toString().equals(params.get("id")))))
        .thenThrow(new DuplicateKeyException("idx_salles_wikidata_id"));

    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(
            Map.of(
                salleEnConflit, new EnrichissementSalle("Q1", null, null, null, null),
                salleOk, new EnrichissementSalle("Q2", null, null, null, null)));
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    int nb = service.enrichir();

    assertEquals(1, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("enrichissement-wikidata"), anyString(), anyMap());
  }

  // #1189 : une collision wikidata_id est un signal plus fort qu'une simple coïncidence de
  // concert — fusion automatique si les deux salles ont des coordonnées connues et proches.
  @Test
  void enrichir_collisionWikidataIdCoordonneesProches_fusionneAutomatiquement() {
    UUID salleId = UUID.randomUUID();
    UUID porteuseId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    when(jdbc.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("idx"));
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE wikidata_id"), anyMap()))
        .thenReturn(List.of(Map.of("id", porteuseId, "latitude", 45.8336, "longitude", 1.2611)));
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE id ="), anyMap()))
        .thenReturn(List.of(new HashMap<>(Map.of("latitude", 45.8337, "longitude", 1.2612))));

    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", null, null, null, null)));
    FusionSallesClePorteeService fusion = mock(FusionSallesClePorteeService.class);
    SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            fusion,
            signalement);
    int nb = service.enrichir();

    assertEquals(1, nb);
    verify(fusion).fusionnerUnePaire(porteuseId, salleId);
    verify(signalement, never()).signaler(anyString(), anyString(), any(), anyString(), any());
  }

  // #1189 : sans coordonnées connues des deux côtés, aucune preuve positive ne justifie une
  // fusion automatique — signalement seul pour revue manuelle.
  @Test
  void enrichir_collisionWikidataIdSansCoordonnees_signaleSansFusionner() {
    UUID salleId = UUID.randomUUID();
    UUID porteuseId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    when(jdbc.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("idx"));
    Map<String, Object> porteuseSansCoordonnees = new HashMap<>();
    porteuseSansCoordonnees.put("id", porteuseId);
    porteuseSansCoordonnees.put("latitude", null);
    porteuseSansCoordonnees.put("longitude", null);
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE wikidata_id"), anyMap()))
        .thenReturn(List.of(porteuseSansCoordonnees));

    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", null, null, null, null)));
    FusionSallesClePorteeService fusion = mock(FusionSallesClePorteeService.class);
    SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            fusion,
            signalement);
    int nb = service.enrichir();

    assertEquals(0, nb);
    verify(fusion, never()).fusionnerUnePaire(any(), any());
    verify(signalement)
        .signaler(eq("collision_wikidata_salle"), eq("salle"), eq(salleId), anyString(), any());
  }

  // bilan de panne absent jusqu'ici sur les sources d'enrichissement — panne Overpass de
  // 3+ mois jamais remontée dans logs_admin. Panne totale (3 appels, 3 erreurs) doit escalader en
  // niveau error.
  @Test
  void enrichir_sourceAvecBilanFetchPanneTotale_loggeErreurDansLogsAdmin() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("overpass");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(3, 3));
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    service.enrichir();

    verify(logsAdminWorker).inserer(eq("error"), eq("worker"), anyString(), anyMap());
  }

  @Test
  void enrichir_sourceAvecBilanFetchAucunAppel_neLoggeAucunBilan() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("overpass");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(0, 0));
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    service.enrichir();

    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }

  // le streak de chronicité (persisté dans app_config) doit survivre entre deux cycles
  // enrichir() distincts sur la même instance — un taux d'échec Overpass élevé répété à chaque
  // nuit doit finir par être qualifié de chronique, pas traité comme un warn isolé à chaque fois.
  @Test
  void enrichir_echecOverpassPersistantSurTroisCycles_qualifieLeMessageDeChronique() {
    UUID salleId = UUID.randomUUID();
    SalleAEnrichir candidat = new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null);
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    java.util.concurrent.atomic.AtomicReference<String> valeurAppConfig =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(String.class)))
        .thenAnswer(invocation -> valeurAppConfig.get());
    when(jdbc.update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO app_config"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any()))
        .thenAnswer(
            invocation -> {
              Map<String, ?> params = invocation.getArgument(1);
              valeurAppConfig.set((String) params.get("json"));
              return 1;
            });

    SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("overpass");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(5, 2));
    LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    service.enrichir();
    service.enrichir();
    service.enrichir();

    verify(logsAdminWorker, times(3)).inserer(eq("warn"), eq("worker"), anyString(), anyMap());
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("worker"),
            org.mockito.ArgumentMatchers.contains("chronique depuis 3 cycles consécutifs"),
            anyMap());
  }
}
