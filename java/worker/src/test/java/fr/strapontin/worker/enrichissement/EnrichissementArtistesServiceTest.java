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

import fr.strapontin.sources.ArtisteAEnrichir;
import fr.strapontin.sources.EnrichissementArtiste;
import fr.strapontin.sources.SourceEnrichissementArtiste;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link EnrichissementArtistesService} — orchestration multi-sources et
 * cross-validation P6262.
 *
 * @since 2.6.0
 * @author Kwaite
 */
class EnrichissementArtistesServiceTest {

  @SuppressWarnings("unchecked")
  private static NamedParameterJdbcTemplate jdbcAvecCandidats(
      final List<ArtisteAEnrichir> candidats, final Map<UUID, String> attractionIdsExistants) {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(candidats);
    // Par défaut une ligne affectée (succès) — sans quoi le défaut Mockito (0) ferait passer
    // systématiquement l'INSERT artiste_sources pour un conflit réel (#1191).
    when(jdbc.update(anyString(), anyMap())).thenReturn(1);
    final List<Map<String, Object>> lignes =
        attractionIdsExistants.entrySet().stream()
            .map(
                e -> {
                  final Map<String, Object> ligne = new HashMap<>();
                  ligne.put("artiste_id", e.getKey());
                  ligne.put("identifiant_externe", e.getValue());
                  return ligne;
                })
            .toList();
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(lignes);
    return jdbc;
  }

  private static LogsAdminWorker logsAdminWorkerMock() {
    return mock(LogsAdminWorker.class);
  }

  @Test
  void enrichir_aucunCandidat_retourneZeroSansAppelerLesSources() {
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(), Map.of());
    final SourceEnrichissementArtiste source = mock(SourceEnrichissementArtiste.class);

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(source), logsAdminWorkerMock(), mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(0, nb);
    verify(source, never()).enrichirLot(any());
  }

  @Test
  void enrichir_wikidataTrouveDeezerId_deezerConfirmeLeNom() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteId, "Daft Punk", "daft punk", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(List.of(candidat)))
        .thenReturn(
            Map.of(artisteId, new EnrichissementArtiste("Q167023", null, "27", null, null)));

    final SourceEnrichissementArtiste deezer = mock(SourceEnrichissementArtiste.class);
    final ArtisteAEnrichir candidatAvecDeezer =
        new ArtisteAEnrichir(artisteId, "Daft Punk", "daft punk", "Q167023", "27");
    when(deezer.enrichirLot(List.of(candidatAvecDeezer)))
        .thenReturn(
            Map.of(artisteId, new EnrichissementArtiste("Q167023", "Daft Punk", "27", null, null)));

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc,
            List.of(wikidata, deezer),
            logsAdminWorkerMock(),
            mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(1, nb);
    verify(jdbc)
        .update(
            anyString(),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    "Q167023".equals(params.get("wikidataId"))
                        && "Daft Punk".equals(params.get("nomAffiche"))
                        && "27".equals(params.get("deezerId"))));
  }

  @Test
  void enrichir_wikidataFournitAlias_persisteAliasWikidata() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "-M-", "m", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste(
                    "Q313039", "Matthieu Chédid", null, null, List.of("-M-", "Matthieu Chedid"))));

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorkerMock(), mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("alias_wikidata"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    Arrays.equals(
                        new String[] {"-M-", "Matthieu Chedid"},
                        (String[]) params.get("aliasWikidata"))));
  }

  // La fusion multi-sources (wikidata puis deezer) ne doit jamais perdre un alias découvert par
  // wikidata au profit d'une source suivante qui n'en fournit aucun.
  @Test
  void enrichir_deuxSourcesSeuleWikidataFournitAlias_fusionConserveLAlias() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "-M-", "m", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(List.of(candidat)))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q313039", null, "12345", null, List.of("-M-"))));

    final SourceEnrichissementArtiste deezer = mock(SourceEnrichissementArtiste.class);
    final ArtisteAEnrichir candidatAvecDeezer =
        new ArtisteAEnrichir(artisteId, "-M-", "m", "Q313039", "12345");
    when(deezer.enrichirLot(List.of(candidatAvecDeezer)))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q313039", "Matthieu Chédid", "12345", null, null)));

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc,
            List.of(wikidata, deezer),
            logsAdminWorkerMock(),
            mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("alias_wikidata"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params ->
                    Arrays.equals(new String[] {"-M-"}, (String[]) params.get("aliasWikidata"))));
  }

  @Test
  void enrichir_p6262Absent_insereAutomatiquement() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q3161164", "Jain", null, "K8vZ917oGV7", null)));

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorkerMock(), mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO artiste_sources"),
            org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(
                params -> "K8vZ917oGV7".equals(params.get("tmId"))));
  }

  @Test
  void enrichir_p6262Identique_neFaitRien() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc =
        jdbcAvecCandidats(List.of(candidat), Map.of(artisteId, "K8vZ917oGV7"));

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q3161164", "Jain", null, "K8vZ917oGV7", null)));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorker, mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO artiste_sources"), anyMap());
    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), any());
  }

  @Test
  void enrichir_p6262Different_loggeSansEcraser() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc =
        jdbcAvecCandidats(List.of(candidat), Map.of(artisteId, "ANCIEN_ID_FAUX"));

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q3161164", "Jain", null, "K8vZ917oGV7", null)));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorker, mock(SignalementMatchingService.class));
    service.enrichir();

    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO artiste_sources"), anyMap());
    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("warn"),
            org.mockito.ArgumentMatchers.eq("enrichissement-wikidata"),
            any(),
            any());
  }

  // Régression : persister uniquement à la toute fin perdrait tout le travail déjà résolu en cas
  // d'interruption avant la fin du cycle (découverte séquentielle, rate-limitée) — la persistance
  // doit avoir lieu lot par lot.
  @Test
  void enrichir_plusDeCandidatsQueLaTailleDeLot_persisteParLotAvantLeLotSuivant() {
    final List<ArtisteAEnrichir> candidats = new ArrayList<>();
    final Map<UUID, EnrichissementArtiste> reponseAttendue = new HashMap<>();
    for (int i = 0; i < 30; i++) {
      final UUID id = UUID.randomUUID();
      candidats.add(new ArtisteAEnrichir(id, "Artiste" + i, "artiste" + i, null, null));
      reponseAttendue.put(id, new EnrichissementArtiste("Q" + i, null, null, null, null));
    }
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(candidats, Map.of());

    final SourceEnrichissementArtiste source = mock(SourceEnrichissementArtiste.class);
    when(source.enrichirLot(any()))
        .thenAnswer(
            invocation -> {
              final List<ArtisteAEnrichir> lot = invocation.getArgument(0);
              final Map<UUID, EnrichissementArtiste> resultat = new HashMap<>();
              for (ArtisteAEnrichir a : lot) {
                resultat.put(a.id(), reponseAttendue.get(a.id()));
              }
              return resultat;
            });

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(source), logsAdminWorkerMock(), mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(30, nb);
    verify(source, times(2)).enrichirLot(any());
    final InOrder ordre = inOrder(source, jdbc);
    ordre.verify(source).enrichirLot(any());
    ordre
        .verify(jdbc, times(25))
        .update(org.mockito.ArgumentMatchers.contains("UPDATE artistes"), anyMap());
    ordre.verify(source).enrichirLot(any());
    ordre
        .verify(jdbc, times(5))
        .update(org.mockito.ArgumentMatchers.contains("UPDATE artistes"), anyMap());
  }

  // Régression prod (2 juillet 2026) : même classe de bug que côté salles — deux artistes
  // distincts matchés sur le même wikidataId doivent voir le conflit isolé et loggé, sans tuer le
  // reste du lot ni déclencher une cross-validation P6262 basée sur un enrichissement rejeté.
  @Test
  void enrichir_wikidataIdDejaAttribueAUnAutreArtiste_loggeLeConflitEtSauteLaCrossValidation() {
    final UUID artisteEnConflit = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteEnConflit, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());
    when(jdbc.update(org.mockito.ArgumentMatchers.contains("UPDATE artistes"), anyMap()))
        .thenThrow(new DuplicateKeyException("idx_artistes_wikidata_id"));

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteEnConflit,
                new EnrichissementArtiste("Q1", "Jain", null, "K8vZ917oGV7", null)));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorker, mock(SignalementMatchingService.class));
    final int nb = service.enrichir();

    assertEquals(0, nb);
    verify(jdbc, never())
        .update(org.mockito.ArgumentMatchers.contains("INSERT INTO artiste_sources"), anyMap());
    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("warn"),
            org.mockito.ArgumentMatchers.eq("enrichissement-wikidata"),
            anyString(),
            anyMap());
  }

  // #1190 : jamais de fusion automatique côté artiste (aucun signal géographique de
  // cross-validation) — la collision doit au moins être signalée pour revue manuelle.
  @Test
  void enrichir_wikidataIdDejaAttribueAUnAutreArtiste_signaleLaCollision() {
    final UUID artisteEnConflit = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteEnConflit, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());
    when(jdbc.update(org.mockito.ArgumentMatchers.contains("UPDATE artistes"), anyMap()))
        .thenThrow(new DuplicateKeyException("idx_artistes_wikidata_id"));

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(artisteEnConflit, new EnrichissementArtiste("Q1", "Jain", null, null, null)));
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorkerMock(), signalement);
    service.enrichir();

    verify(signalement)
        .signaler(
            eq("collision_wikidata_artiste"),
            eq("artiste"),
            eq(artisteEnConflit),
            anyString(),
            any());
  }

  // #1191 : cet identifiant Ticketmaster est déjà attribué à un autre artiste — l'INSERT ON
  // CONFLICT DO NOTHING échoue silencieusement (0 ligne affectée), contrairement à la collision
  // wikidata_id ce cas n'avait jusqu'ici aucune trace.
  @Test
  void enrichir_p6262IdentifiantTmDejaAttribueAUnAutreArtiste_signaleLaReattribution() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat = new ArtisteAEnrichir(artisteId, "Jain", "jain", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());
    when(jdbc.update(
            org.mockito.ArgumentMatchers.contains("INSERT INTO artiste_sources"), anyMap()))
        .thenReturn(0);

    final SourceEnrichissementArtiste wikidata = mock(SourceEnrichissementArtiste.class);
    when(wikidata.enrichirLot(any()))
        .thenReturn(
            Map.of(
                artisteId,
                new EnrichissementArtiste("Q3161164", "Jain", null, "K8vZ917oGV7", null)));
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorkerMock(), signalement);
    service.enrichir();

    verify(signalement)
        .signaler(eq("reattribution_tm_artiste"), eq("artiste"), eq(artisteId), anyString(), any());
  }

  // #1194 : une ambiguïté prélevée sur une source implémentant SourceAvecAmbiguitesDecouverte
  // doit être signalée, distinguable d'une non-découverte ordinaire.
  @Test
  void enrichir_sourceAvecAmbiguitesPrelevees_signaleChacune() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteId, "Van Halen", "van halen", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final var wikidata =
        mock(
            SourceEnrichissementArtiste.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(fr.strapontin.sources.SourceAvecAmbiguitesDecouverte.class));
    when(wikidata.enrichirLot(any())).thenReturn(Map.of());
    when(((fr.strapontin.sources.SourceAvecAmbiguitesDecouverte) wikidata).preleverAmbiguites())
        .thenReturn(Set.of(artisteId));
    final SignalementMatchingService signalement = mock(SignalementMatchingService.class);

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(wikidata), logsAdminWorkerMock(), signalement);
    service.enrichir();

    verify(signalement)
        .signaler(eq("wikidata_ambiguite"), eq("artiste"), eq(artisteId), anyString(), any());
  }

  // bilan de panne absent jusqu'ici sur les sources d'enrichissement artiste.
  @Test
  void enrichir_sourceAvecBilanFetchPanneTotale_loggeErreurDansLogsAdmin() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteId, "Daft Punk", "daft punk", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste source =
        mock(
            SourceEnrichissementArtiste.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(fr.strapontin.sources.SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("wikidata");
    when(((fr.strapontin.sources.SourceAvecBilanFetch) source).preleverBilan())
        .thenReturn(new fr.strapontin.core.BilanFetch(3, 3));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(source), logsAdminWorker, mock(SignalementMatchingService.class));
    service.enrichir();

    verify(logsAdminWorker)
        .inserer(
            org.mockito.ArgumentMatchers.eq("error"),
            org.mockito.ArgumentMatchers.eq("worker"),
            anyString(),
            anyMap());
  }

  @Test
  void enrichir_sourceAvecBilanFetchAucunAppel_neLoggeAucunBilan() {
    final UUID artisteId = UUID.randomUUID();
    final ArtisteAEnrichir candidat =
        new ArtisteAEnrichir(artisteId, "Daft Punk", "daft punk", null, null);
    final NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of(candidat), Map.of());

    final SourceEnrichissementArtiste source =
        mock(
            SourceEnrichissementArtiste.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(fr.strapontin.sources.SourceAvecBilanFetch.class));
    when(source.enrichirLot(any())).thenReturn(Map.of());
    when(source.nom()).thenReturn("wikidata");
    when(((fr.strapontin.sources.SourceAvecBilanFetch) source).preleverBilan())
        .thenReturn(new fr.strapontin.core.BilanFetch(0, 0));
    final LogsAdminWorker logsAdminWorker = logsAdminWorkerMock();

    final EnrichissementArtistesService service =
        new EnrichissementArtistesService(
            jdbc, List.of(source), logsAdminWorker, mock(SignalementMatchingService.class));
    service.enrichir();

    verify(logsAdminWorker, never()).inserer(anyString(), anyString(), anyString(), anyMap());
  }
}
