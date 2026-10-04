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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests unitaires pour {@link EnrichissementSallesService} (dépendances mockées).
 *
 * @since 2.6.0
 * @author Kwaite
 */
class EnrichissementSallesServiceTest {

  @SuppressWarnings("unchecked")
  private static NamedParameterJdbcTemplate jdbcAvecCandidats(
      final List<SalleAEnrichir> candidats) {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(candidats);
    return jdbc;
  }

  private static LogsAdminWorker logsAdminWorkerMock() {
    return mock(LogsAdminWorker.class);
  }

  // instance réelle adossée au jdbc mocké du test (comportement passthrough par défaut, aucun
  // streak persisté ne survit entre deux tests indépendants) — cf. WorkerStatsServiceTest.
  private static SuiviChroniciteService suiviChronicite(final NamedParameterJdbcTemplate jdbc) {
    return new SuiviChroniciteService(jdbc, new ObjectMapper());
  }

  @Test
  void enrichir_aucunCandidat_retourneZeroSansAppelerLesSources() {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of());
    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(0, nb);
    verify(source, never()).enrichirLot(any());
  }

  // Un bilan d'appels/erreurs à 0 échec ne détecte pas une source qui répond systématiquement
  // "200 OK, aucun résultat" (ex. panne géographique silencieuse d'une instance publique) — un
  // cycle avec un
  // nombre significatif de candidats et 0 salle enrichie doit être journalisé séparément.
  @Test
  void enrichir_vingtCandidatsZeroEnrichis_journaliseUneAlerte() {
    final List<SalleAEnrichir> candidats = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      candidats.add(
          new SalleAEnrichir(
              UUID.randomUUID(), "Salle " + i, "salle " + i, null, null, null, null, null));
    }
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any())).thenReturn(Map.of());
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(0, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_moinsDeVingtCandidatsZeroEnrichis_neJournalisePasDAlerte() {
    final SalleAEnrichir candidat =
        new SalleAEnrichir(UUID.randomUUID(), "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any())).thenReturn(Map.of());
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
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
    final List<SalleAEnrichir> candidats = new ArrayList<>();
    final List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      final UUID id = UUID.randomUUID();
      ids.add(id);
      candidats.add(
          new SalleAEnrichir(id, "Salle " + i, "salle " + i, null, null, null, null, null));
    }
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    // 3 succès sur 30 (10%), répartis sur les deux premiers lots de persistance (25 puis 5).
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              final List<SalleAEnrichir> lot = invocation.getArgument(0);
              final Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) {
                if (ids.indexOf(s.id()) < 3) {
                  resultat.put(
                      s.id(), new EnrichissementSalle("Q" + s.id(), null, null, null, null));
                }
              }
              return resultat;
            });
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(3, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_vingtCandidatsDixEnrichisBonTaux_neJournalisePasDAlerte() {
    final List<SalleAEnrichir> candidats = new ArrayList<>();
    final List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      final UUID id = UUID.randomUUID();
      ids.add(id);
      candidats.add(
          new SalleAEnrichir(id, "Salle " + i, "salle " + i, null, null, null, null, null));
    }
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);
    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    // 10 succès sur 20 (50%, largement au-dessus du seuil).
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              final List<SalleAEnrichir> lot = invocation.getArgument(0);
              final Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) {
                if (ids.indexOf(s.id()) < 10) {
                  resultat.put(
                      s.id(), new EnrichissementSalle("Q" + s.id(), null, null, null, null));
                }
              }
              return resultat;
            });
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(10, nb);
    verify(logsAdminWorker, never())
        .inserer(anyString(), eq("reconciliation-nocturne"), anyString(), anyMap());
  }

  @Test
  void enrichir_premiereSourceTrouveWikidataId_wikidataUtiliseLeQidPropage() {
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final SourceEnrichissementSalle nominatim = mock(SourceEnrichissementSalle.class);
    when(nominatim.enrichirLot(List.of(candidat)))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q123", null, null, null, null)));

    final SourceEnrichissementSalle wikidata = mock(SourceEnrichissementSalle.class);
    final SalleAEnrichir candidatAvecQid =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, "Q123", null, null);
    when(wikidata.enrichirLot(List.of(candidatAvecQid)))
        .thenReturn(
            Map.of(salleId, new EnrichissementSalle("Q123", "Zénith", "Nantes", null, null)));

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(nominatim, wikidata),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

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
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Salle X", "salle x", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final SourceEnrichissementSalle source1 = mock(SourceEnrichissementSalle.class);
    when(source1.enrichirLot(any())).thenReturn(Map.of());
    final SourceEnrichissementSalle source2 = mock(SourceEnrichissementSalle.class);
    when(source2.enrichirLot(any())).thenReturn(Map.of());
    final SourceEnrichissementSalle source3 =
        mock(SourceEnrichissementSalle.class); // "3e" source ajoutée sans modifier le service
    when(source3.enrichirLot(any()))
        .thenReturn(
            Map.of(salleId, new EnrichissementSalle(null, "Salle X Canonique", null, null, null)));

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source1, source2, source3),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(1, nb);
    verify(source3, times(1)).enrichirLot(any());
  }

  @Test
  void enrichir_nomAfficheDejaFourniParUneSource_neSecrasePasParSuivante() {
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final SourceEnrichissementSalle source1 = mock(SourceEnrichissementSalle.class);
    when(source1.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", "Nom Un", null, null, null)));
    final SourceEnrichissementSalle source2 = mock(SourceEnrichissementSalle.class);
    when(source2.enrichirLot(any())).thenReturn(Map.of());

    final EnrichissementSallesService service =
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
    final List<SalleAEnrichir> candidats = new ArrayList<>();
    final Map<UUID, EnrichissementSalle> reponseAttendue = new HashMap<>();
    for (int i = 0; i < 30; i++) {
      final UUID id = UUID.randomUUID();
      candidats.add(new SalleAEnrichir(id, "Salle" + i, "salle" + i, null, null, null, null, null));
      reponseAttendue.put(id, new EnrichissementSalle("Q" + i, null, null, null, null));
    }
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats);

    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              final List<SalleAEnrichir> lot = invocation.getArgument(0);
              final Map<UUID, EnrichissementSalle> resultat = new HashMap<>();
              for (SalleAEnrichir s : lot) {
                resultat.put(s.id(), reponseAttendue.get(s.id()));
              }
              return resultat;
            });

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(30, nb);
    verify(source, times(2)).enrichirLot(any());
    final InOrder ordre = inOrder(source, jdbc);
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
    final UUID salleEnConflit = UUID.randomUUID();
    final UUID salleOk = UUID.randomUUID();
    final SalleAEnrichir candidatConflit =
        new SalleAEnrichir(
            salleEnConflit, "Orleans Arena", "orleans arena", null, null, null, null, null);
    final SalleAEnrichir candidatOk =
        new SalleAEnrichir(salleOk, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidatConflit, candidatOk));
    when(jdbc.update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> salleEnConflit.toString().equals(params.get("id")))))
        .thenThrow(new DuplicateKeyException("idx_salles_wikidata_id"));

    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(
            Map.of(
                salleEnConflit, new EnrichissementSalle("Q1", null, null, null, null),
                salleOk, new EnrichissementSalle("Q2", null, null, null, null)));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorker,
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(1, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("enrichissement-wikidata"), anyString(), anyMap());
  }

  private record IssueCollision(
      FusionSallesClePorteeService fusion, SignalementMatchingService signalement) {}

  // Collision entre « salle » (positionnée près de la porteuse) et une porteuse donnée.
  private IssueCollision enrichirAvecCollision(
      final UUID salleId, final String nomSalle, final Map<String, Object> porteuse) {
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, nomSalle, nomSalle, null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    when(jdbc.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("idx"));
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE wikidata_id"), anyMap()))
        .thenReturn(List.of(porteuse));
    final Map<String, Object> salle = new HashMap<>();
    salle.put("nom_norm", nomSalle);
    salle.put("ville", "Paris");
    salle.put("latitude", 48.84374);
    salle.put("longitude", 2.32508);
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE id ="), anyMap()))
        .thenReturn(List.of(salle));

    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", null, null, null, null)));
    final FusionSallesClePorteeService fusion = mock(FusionSallesClePorteeService.class);
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);
    new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            fusion,
            signalement)
        .enrichir();
    return new IssueCollision(fusion, signalement);
  }

  private static Map<String, Object> porteuse(
      final UUID id, final String nomNorm, final String originePosition) {
    final Map<String, Object> porteuse = new HashMap<>();
    porteuse.put("id", id);
    porteuse.put("nom_norm", nomNorm);
    porteuse.put("ville", "Paris");
    porteuse.put("latitude", 48.84375);
    porteuse.put("longitude", 2.32509);
    porteuse.put("origine_position", originePosition);
    return porteuse;
  }

  // Une collision wikidata_id est un signal plus fort qu'une simple coïncidence de concert :
  // fusion automatique si noms compatibles et porteuse positionnée par ses lieux publiés.
  @Test
  void enrichir_collisionWikidataIdMemeLieu_fusionneAutomatiquement() {
    final UUID salleId = UUID.randomUUID();
    final UUID porteuseId = UUID.randomUUID();

    final IssueCollision issue =
        enrichirAvecCollision(
            salleId,
            "theatre de poche montparnasse",
            porteuse(porteuseId, "theatre de poche montparnasse paris", "sources"));

    verify(issue.fusion()).fusionnerUnePaire(porteuseId, salleId);
    verify(issue.signalement(), never())
        .signaler(anyString(), anyString(), any(), anyString(), any());
  }

  // Théâtre Montparnasse porteur à tort de l'élément du Théâtre de Poche : la position de la
  // porteuse est celle de l'élément, la proximité ne prouve rien.
  @Test
  void enrichir_collisionPorteusePositionneeParWikidata_signaleSansFusionner() {
    final UUID salleId = UUID.randomUUID();

    final IssueCollision issue =
        enrichirAvecCollision(
            salleId,
            "theatre de poche montparnasse",
            porteuse(UUID.randomUUID(), "theatre de poche montparnasse paris", "wikidata"));

    verify(issue.fusion(), never()).fusionnerUnePaire(any(), any());
    verify(issue.signalement())
        .signaler(eq("collision_wikidata_salle"), eq("salle"), eq(salleId), anyString(), any());
  }

  @Test
  void enrichir_collisionNomsDeDeuxLieuxVoisins_signaleSansFusionner() {
    final UUID salleId = UUID.randomUUID();

    final IssueCollision issue =
        enrichirAvecCollision(
            salleId,
            "theatre de poche montparnasse",
            porteuse(UUID.randomUUID(), "theatre montparnasse", "sources"));

    verify(issue.fusion(), never()).fusionnerUnePaire(any(), any());
    verify(issue.signalement())
        .signaler(eq("collision_wikidata_salle"), eq("salle"), eq(salleId), anyString(), any());
  }

  // #1189 : sans coordonnées connues des deux côtés, aucune preuve positive ne justifie une
  // fusion automatique — signalement seul pour revue manuelle.
  @Test
  void enrichir_collisionWikidataIdSansCoordonnees_signaleSansFusionner() {
    final UUID salleId = UUID.randomUUID();
    final UUID porteuseId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));
    when(jdbc.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("idx"));
    final Map<String, Object> porteuseSansCoordonnees = new HashMap<>();
    porteuseSansCoordonnees.put("id", porteuseId);
    porteuseSansCoordonnees.put("latitude", null);
    porteuseSansCoordonnees.put("longitude", null);
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE wikidata_id"), anyMap()))
        .thenReturn(List.of(porteuseSansCoordonnees));

    final SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);
    when(source.enrichirLot(any()))
        .thenReturn(Map.of(salleId, new EnrichissementSalle("Q1", null, null, null, null)));
    final FusionSallesClePorteeService fusion = mock(FusionSallesClePorteeService.class);
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(source),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            fusion,
            signalement);
    final int nb = service.enrichir();

    assertEquals(0, nb);
    verify(fusion, never()).fusionnerUnePaire(any(), any());
    verify(signalement)
        .signaler(eq("collision_wikidata_salle"), eq("salle"), eq(salleId), anyString(), any());
  }

  // bilan de panne absent jusqu'ici sur les sources d'enrichissement — panne d'une source publique
  // de
  // 3+ mois jamais remontée dans logs_admin. Panne totale (3 appels, 3 erreurs) doit escalader en
  // niveau error.
  @Test
  void enrichir_sourceAvecBilanFetchPanneTotale_loggeErreurDansLogsAdmin() {
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("nominatim");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(3, 3));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
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
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("nominatim");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(0, 0));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
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

  // #1218 : une salle dont un candidat Wikidata a été écarté sur divergence GPS (ambiguïté,
  // distincte d'une non-découverte ordinaire) doit être signalée pour revue manuelle.
  @Test
  void enrichir_sourceAvecAmbiguitesPrelevees_signaleChacune() {
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Alhambra", "alhambra", "Paris", "75", null, 48.8716, 2.3616);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final var wikidata =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(fr.strapontin.sources.SourceAvecAmbiguitesDecouverte.class));
    when(wikidata.enrichirLot(any())).thenReturn(Map.of());
    when(((fr.strapontin.sources.SourceAvecAmbiguitesDecouverte) wikidata).preleverAmbiguites())
        .thenReturn(java.util.Set.of(salleId));
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    final EnrichissementSallesService service =
        new EnrichissementSallesService(
            jdbc,
            List.of(wikidata),
            logsAdminWorkerMock(),
            suiviChronicite(jdbc),
            mock(FusionSallesClePorteeService.class),
            signalement);
    service.enrichir();

    verify(signalement)
        .signaler(
            eq("wikidata_coordonnees_divergentes"), eq("salle"), eq(salleId), anyString(), any());
  }

  // le streak de chronicité (persisté dans app_config) doit survivre entre deux cycles
  // enrichir() distincts sur la même instance — un taux d'échec élevé répété à chaque
  // nuit doit finir par être qualifié de chronique, pas traité comme un warn isolé à chaque fois.
  @Test
  void enrichir_echecSourcePersistantSurTroisCycles_qualifieLeMessageDeChronique() {
    final UUID salleId = UUID.randomUUID();
    final SalleAEnrichir candidat =
        new SalleAEnrichir(salleId, "Zenith", "zenith", null, null, null, null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat));

    final AtomicReference<String> valeurAppConfig = new AtomicReference<>();
    when(jdbc.queryForObject(
            anyString(), org.mockito.ArgumentMatchers.<Map<String, ?>>any(), eq(String.class)))
        .thenAnswer(_ -> valeurAppConfig.get());
    when(jdbc.update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO app_config"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any()))
        .thenAnswer(
            invocation -> {
              final Map<String, ?> params = invocation.getArgument(1);
              valeurAppConfig.set((String) params.get("json"));
              return 1;
            });

    final SourceEnrichissementSalle source =
        mock(
            SourceEnrichissementSalle.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("nominatim");
    when(((SourceAvecBilanFetch) source).preleverBilan()).thenReturn(new BilanFetch(5, 2));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementSallesService service =
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
