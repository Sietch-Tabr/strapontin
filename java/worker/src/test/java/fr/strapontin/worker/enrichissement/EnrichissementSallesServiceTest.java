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

import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.SourceEnrichissementSalle;
import fr.strapontin.worker.LogsAdminWorker;
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

  @Test
  void enrichir_aucunCandidat_retourneZeroSansAppelerLesSources() {
    NamedParameterJdbcTemplate jdbc = jdbcAvecCandidats(List.of());
    SourceEnrichissementSalle source = mock(SourceEnrichissementSalle.class);

    EnrichissementSallesService service =
        new EnrichissementSallesService(jdbc, List.of(source), logsAdminWorkerMock());
    int nb = service.enrichir();

    assertEquals(0, nb);
    verify(source, never()).enrichirLot(any());
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
        new EnrichissementSallesService(jdbc, List.of(overpass, wikidata), logsAdminWorkerMock());
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
            jdbc, List.of(source1, source2, source3), logsAdminWorkerMock());
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
        new EnrichissementSallesService(jdbc, List.of(source1, source2), logsAdminWorkerMock());
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
        new EnrichissementSallesService(jdbc, List.of(source), logsAdminWorkerMock());
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
  @SuppressWarnings("unchecked")
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
        new EnrichissementSallesService(jdbc, List.of(source), logsAdminWorker);
    int nb = service.enrichir();

    assertEquals(1, nb);
    verify(logsAdminWorker)
        .inserer(eq("warn"), eq("enrichissement-wikidata"), anyString(), anyMap());
  }
}
