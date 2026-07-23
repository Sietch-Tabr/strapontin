/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.EnrichissementSalle;
import fr.strapontin.sources.SalleAEnrichir;
import fr.strapontin.sources.WikidataSalleSource;
import fr.strapontin.worker.LogsAdminWorker;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/// Tests unitaires pour {@link CoherenceWikidataSalleService} (#1005).
///
/// @since 2.7.5
/// @author Kwaite
class CoherenceWikidataSalleServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private WikidataSalleSource wikidata;
  private LogsAdminWorker logsAdminWorker;
  private CoherenceWikidataSalleService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(NamedParameterJdbcTemplate.class);
    wikidata = mock(WikidataSalleSource.class);
    logsAdminWorker = mock(LogsAdminWorker.class);
    service = new CoherenceWikidataSalleService(jdbc, wikidata, logsAdminWorker);
  }

  @SuppressWarnings("unchecked")
  private void simulerCandidats(List<SalleAEnrichir> candidats) {
    when(jdbc.query(
            org.mockito.ArgumentMatchers.anyString(),
            any(Map.class),
            any(org.springframework.jdbc.core.RowMapper.class)))
        .thenReturn(candidats);
  }

  /// Simule le résultat de `chargerVillesConcertsReelles` en pilotant directement le
  /// {@link RowCallbackHandler} avec un {@link ResultSet} mocké — sans ce stub, l'appel réel
  /// n'est jamais déclenché en test (Mockito no-op par défaut sur un `void`), ce qui correspond
  /// au cas « aucun concert réel connu » (repli sur `salles.ville`, comportement historique).
  private void simulerVillesConcertsReelles(Map<UUID, List<String>> villesParSalle) {
    doAnswer(
            invocation -> {
              RowCallbackHandler handler = invocation.getArgument(2);
              for (Map.Entry<UUID, List<String>> entree : villesParSalle.entrySet()) {
                for (String ville : entree.getValue()) {
                  ResultSet rs = mock(ResultSet.class);
                  when(rs.getObject("salle_id", UUID.class)).thenReturn(entree.getKey());
                  when(rs.getString("ville")).thenReturn(ville);
                  handler.processRow(rs);
                }
              }
              return null;
            })
        .when(jdbc)
        .query(
            org.mockito.ArgumentMatchers.anyString(),
            any(Map.class),
            any(RowCallbackHandler.class));
  }

  @Test
  void verifier_aucunCandidat_neContactePasWikidataEtRetourneZero() {
    simulerCandidats(List.of());

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(wikidata, never()).enrichirLot(anyList());
  }

  @Test
  void verifier_villeCoherente_neJournaliseRien() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", "Lille", null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_villeIncoherente_journaliseEtCompteUneIncoherence() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q428765");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q428765", "Musée des Beaux-Arts de Nancy", "Nancy", null, null)));

    int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Theatre Municipal"),
            any());
  }

  @Test
  void verifier_communeWikidataInconnue_neJournalisePasEtNeCompteRien() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", null, null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_salleSansResultatWikidata_ignoreeSansErreur() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList())).thenReturn(Map.of());

    int resultat = service.verifier();

    assertEquals(0, resultat);
  }

  // #1009 : faux positifs constatés lors de la première nuit de fonctionnement (arrondissements
  // Paris/Lyon/Marseille, quartiers informels sans commune propre, communes fusionnées,
  // intercommunalités) — aucun ne doit journaliser d'incohérence.
  static Stream<Arguments> villesCoherentesMalgreDivergenceTextuelle() {
    return Stream.of(
        Arguments.of(
            "La Sainte-Chapelle", "1er arrondissement de Paris", "Saint-Germain-l'Auxerrois"),
        Arguments.of("Eglise Saint-Paul-Saint-Louis", "4e arrondissement de Paris", "Notre-Dame"),
        Arguments.of("Accor Arena", "12e arrondissement de Paris", "Bercy"),
        Arguments.of("Théâtre des Bouffes du Nord", "Paris", "10ᵉ arrondissement de Paris"),
        Arguments.of("L'Archipel", "Nanterre", "La Défense"),
        Arguments.of(
            "Théâtre Antique d'Orange",
            "Orange",
            "communauté de communes du Pays d'Orange en Provence"),
        Arguments.of("Parc Jean de la Fontaine", "Oullins-Pierre-Bénite", "Pierre-Bénite"));
  }

  @ParameterizedTest
  @MethodSource("villesCoherentesMalgreDivergenceTextuelle")
  void verifier_divergenceGranulariteAdministrative_neJournalisePasDIncoherence(
      String nomSalle, String villeSalle, String communeWikidata) {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id, nomSalle, Normalisation.normaliserTexte(nomSalle), villeSalle, "75", "Q1");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(id, new EnrichissementSalle("Q1", nomSalle, communeWikidata, null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_villeReellementDifferente_resteJournaliseeMalgreLeReplaiContainment() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Bikini", "le bikini", "Toulouse", "31", "Q3220442");
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q3220442", "Le Bikini", "Ramonville-Saint-Agne", null, null)));

    int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Le Bikini"),
            any());
  }

  // #1021 : le mauvais rapprochement a corrompu salles.ville en même temps que wikidata_id (les
  // deux valeurs pointent vers la même mauvaise entité) — une comparaison contre salles.ville
  // seule trouve alors toujours les deux cohérents entre eux, angle mort total. concerts.ville
  // (indépendante, jamais touchée par l'enrichissement) doit prendre le dessus et révéler
  // l'incohérence que salles.ville masquait.
  @Test
  void verifier_villeSalleCorrompueMaisConcertsReelsDivergents_journaliseIncoherence() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Espace Saint-Exupéry", "espace saint exupery", "Rubelles", "95", "Q120266538");
    simulerCandidats(List.of(salle));
    simulerVillesConcertsReelles(Map.of(id, List.of("Franconville-la-Garenne")));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q120266538", "Espace Saint-Exupéry", "Rubelles", null, null)));

    int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Espace Saint-Exupéry"),
            any());
  }

  // #1021 : à l'inverse, si salles.ville diverge textuellement de la commune Wikidata mais que
  // les concerts réels (source indépendante) confirment la même ville, ce n'est pas une vraie
  // incohérence — concerts.ville doit primer sur salles.ville plutôt que de générer un faux
  // positif.
  @Test
  void verifier_concertsReelsCoherentsMalgreVilleSalleDivergente_neJournalisePasDIncoherence() {
    UUID id = UUID.randomUUID();
    SalleAEnrichir salle =
        new SalleAEnrichir(id, "Zenith", "zenith", "Ancienne Ville Erronee", "59", "Q1");
    simulerCandidats(List.of(salle));
    simulerVillesConcertsReelles(Map.of(id, List.of("Lille")));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Zenith", "Lille", null, null)));

    int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }
}
