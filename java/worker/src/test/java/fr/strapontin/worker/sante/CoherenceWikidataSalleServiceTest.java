/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tests unitaires pour {@link CoherenceWikidataSalleService}.
 *
 * @since 2.7.5
 * @author Kwaite
 */
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
    service =
        new CoherenceWikidataSalleService(
            jdbc, wikidata, logsAdminWorker, mock(SignalementMatchingService.class));
  }

  @SuppressWarnings("unchecked")
  private void simulerCandidats(final List<SalleAEnrichir> candidats) {
    when(jdbc.query(org.mockito.ArgumentMatchers.anyString(), any(Map.class), any(RowMapper.class)))
        .thenReturn(candidats);
  }

  /**
   * Simule le résultat de {@code chargerVillesConcertsReelles} en pilotant directement le {@link
   * RowCallbackHandler} avec un {@link ResultSet} mocké — sans ce stub, l'appel réel n'est jamais
   * déclenché en test (Mockito no-op par défaut sur un {@code void}), ce qui correspond au cas « aucun
   * concert réel connu » (repli sur {@code salles.ville}, comportement historique).
   */
  private void simulerVillesConcertsReelles(final Map<UUID, List<String>> villesParSalle) {
    doAnswer(
            invocation -> {
              final RowCallbackHandler handler = invocation.getArgument(2);
              for (Map.Entry<UUID, List<String>> entree : villesParSalle.entrySet()) {
                for (String ville : entree.getValue()) {
                  final ResultSet rs = mock(ResultSet.class);
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
            org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
            any(RowCallbackHandler.class));
  }

  /**
   * Simule le résultat de {@code chargerSignaturesDejaSignalees}, distinct de {@link
   * #simulerVillesConcertsReelles} par le texte SQL ({@code coherence_wikidata_signalee} vs {@code FROM
   * concerts}) pour ne pas router les deux {@code RowCallbackHandler} sur le même stub.
   */
  private void simulerSignaturesDejaSignalees(final Map<UUID, String> signaturesParSalle) {
    doAnswer(
            invocation -> {
              final RowCallbackHandler handler = invocation.getArgument(2);
              for (Map.Entry<UUID, String> entree : signaturesParSalle.entrySet()) {
                final ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(entree.getKey());
                when(rs.getString("coherence_wikidata_signalee")).thenReturn(entree.getValue());
                handler.processRow(rs);
              }
              return null;
            })
        .when(jdbc)
        .query(
            org.mockito.ArgumentMatchers.contains("coherence_wikidata_signalee"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>any(),
            any(RowCallbackHandler.class));
  }

  @Test
  void verifier_aucunCandidat_neContactePasWikidataEtRetourneZero() {
    simulerCandidats(List.of());

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(wikidata, never()).enrichirLot(anyList());
  }

  @Test
  void verifier_villeCoherente_neJournaliseRien() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1", null, null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", "Lille", null, null)));

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_villeIncoherente_journaliseEtCompteUneIncoherence() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q428765", null, null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q428765", "Musée des Beaux-Arts de Nancy", "Nancy", null, null)));

    final int resultat = service.verifier();

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
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1", null, null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", null, null, null)));

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_salleSansResultatWikidata_ignoreeSansErreur() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1", null, null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList())).thenReturn(Map.of());

    final int resultat = service.verifier();

    assertEquals(0, resultat);
  }

  // faux positifs constatés lors de la première nuit de fonctionnement (arrondissements
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
        Arguments.of("Parc Jean de la Fontaine", "Oullins-Pierre-Bénite", "Pierre-Bénite"),
        Arguments.of("Opéra Bastille", "Quinze-Vingts", "Quinze-Vingts"));
  }

  @ParameterizedTest
  @MethodSource("villesCoherentesMalgreDivergenceTextuelle")
  void verifier_divergenceGranulariteAdministrative_neJournalisePasDIncoherence(
      final String nomSalle, final String villeSalle, final String communeWikidata) {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            nomSalle,
            Normalisation.normaliserTexte(nomSalle),
            villeSalle,
            "75",
            "Q1",
            null,
            null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(id, new EnrichissementSalle("Q1", nomSalle, communeWikidata, null, null)));

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  @Test
  void verifier_villeReellementDifferente_resteJournaliseeMalgreLeReplaiContainment() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Bikini", "le bikini", "Toulouse", "31", "Q3220442", null, null);
    simulerCandidats(List.of(salle));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q3220442", "Le Bikini", "Ramonville-Saint-Agne", null, null)));

    final int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Le Bikini"),
            any());
  }

  // le mauvais rapprochement a corrompu salles.ville en même temps que wikidata_id (les
  // deux valeurs pointent vers la même mauvaise entité) — une comparaison contre salles.ville
  // seule trouve alors toujours les deux cohérents entre eux, angle mort total. concerts.ville
  // (indépendante, jamais touchée par l'enrichissement) doit prendre le dessus et révéler
  // l'incohérence que salles.ville masquait.
  @Test
  void verifier_villeSalleCorrompueMaisConcertsReelsDivergents_journaliseIncoherence() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id,
            "Espace Saint-Exupéry",
            "espace saint exupery",
            "Rubelles",
            "95",
            "Q120266538",
            null,
            null);
    simulerCandidats(List.of(salle));
    simulerVillesConcertsReelles(Map.of(id, List.of("Franconville-la-Garenne")));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q120266538", "Espace Saint-Exupéry", "Rubelles", null, null)));

    final int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Espace Saint-Exupéry"),
            any());
  }

  // Une salle correctement enrichie pour Béthune mais qui absorbe aussi des concerts mal
  // rattachés à Carcassonne doit rester détectée — toutes les villes réelles doivent être
  // cohérentes pour ne rien journaliser, pas seulement une majorité.
  @Test
  void verifier_contaminationPartielle_journaliseIncoherenceMemeAvecUnConcertCoherent() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q46101992", null, null);
    simulerCandidats(List.of(salle));
    simulerVillesConcertsReelles(Map.of(id, List.of("Bethune", "Carcassonne")));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q46101992", "Theatre Municipal de Bethune", "Bethune", null, null)));

    final int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("contamination partielle"),
            any());
  }

  // à l'inverse, si salles.ville diverge textuellement de la commune Wikidata mais que
  // les concerts réels (source indépendante) confirment la même ville, ce n'est pas une vraie
  // incohérence — concerts.ville doit primer sur salles.ville plutôt que de générer un faux
  // positif, et la ville de la salle est alignée sur la commune réelle.
  @Test
  void verifier_concertsReelsCoherentsMalgreVilleSalleDivergente_neJournalisePasDIncoherence() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Zenith", "zenith", "Ancienne Ville Erronee", "59", "Q1", null, null);
    simulerCandidats(List.of(salle));
    simulerVillesConcertsReelles(Map.of(id, List.of("Lille")));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Zenith", "Lille", null, null)));

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(logsAdminWorker, never()).inserer(eq("warn"), any(), any(), any());
    verify(jdbc).update(contains("UPDATE salles SET ville"), anyMap());
  }

  // une salle déjà investiguée et confirmée légitime plusieurs nuits de suite ne doit pas
  // continuer à générer le même WARN indéfiniment.
  @Test
  void verifier_incoherenceDejaSignaleeAvecMemeSignature_neJournalisePasDeNouveau() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q428765", null, null);
    simulerCandidats(List.of(salle));
    simulerSignaturesDejaSignalees(Map.of(id, "Nancy|false"));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q428765", "Musée des Beaux-Arts de Nancy", "Nancy", null, null)));

    final int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }

  // une nouvelle incohérence (commune Wikidata différente de la dernière déjà signalée) doit
  // rester journalisée, même si la salle avait déjà une ancienne incohérence connue.
  @Test
  void verifier_incoherenceDejaSignaleeAvecSignatureDifferente_journaliseDeNouveau() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(
            id, "Theatre Municipal", "theatre municipal", "Bethune", "62", "Q428765", null, null);
    simulerCandidats(List.of(salle));
    simulerSignaturesDejaSignalees(Map.of(id, "Ancienne Commune|false"));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(
            Map.of(
                id,
                new EnrichissementSalle(
                    "Q428765", "Musée des Beaux-Arts de Nancy", "Nancy", null, null)));

    final int resultat = service.verifier();

    assertEquals(1, resultat);
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Theatre Municipal"),
            any());
  }

  // une incohérence résolue (redevenue cohérente) doit réinitialiser la signature mémorisée,
  // pour qu'une future nouvelle incohérence soit à nouveau journalisée.
  @Test
  void verifier_incoherenceResolueMaisSignatureConnue_reinitialiseLaSignature() {
    final UUID id = UUID.randomUUID();
    final SalleAEnrichir salle =
        new SalleAEnrichir(id, "Le Splendid", "le splendid", "Lille", "59", "Q1", null, null);
    simulerCandidats(List.of(salle));
    simulerSignaturesDejaSignalees(Map.of(id, "Ancienne Commune|false"));
    when(wikidata.enrichirLot(anyList()))
        .thenReturn(Map.of(id, new EnrichissementSalle("Q1", "Le Splendid", "Lille", null, null)));

    final int resultat = service.verifier();

    assertEquals(0, resultat);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("coherence_wikidata_signalee"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>argThat(
                p -> p.get("signature") == null && id.toString().equals(p.get("id"))));
  }

  // --- revaliderTypes ---

  @Test
  void revaliderTypes_aucunWikidataId_neContactePasWikidataEtRetourneZero() {
    when(jdbc.queryForList(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(),
            eq(String.class)))
        .thenReturn(List.of());

    final int resultat = service.revaliderTypes();

    assertEquals(0, resultat);
    verify(wikidata, never()).typesInvalides(anyList());
  }

  @Test
  void revaliderTypes_typeDesormaisExclu_metWikidataIdANullEtJournalise() {
    when(jdbc.queryForList(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(),
            eq(String.class)))
        .thenReturn(List.of("Q110597455"));
    when(wikidata.typesInvalides(List.of("Q110597455"))).thenReturn(Set.of("Q110597455"));

    final int resultat = service.revaliderTypes();

    assertEquals(1, resultat);
    verify(jdbc)
        .update(
            org.mockito.ArgumentMatchers.contains("wikidata_id = NULL, latitude = NULL"),
            org.mockito.ArgumentMatchers.<Map<String, ?>>argThat(
                p -> "Q110597455".equals(p.get("qid"))));
    verify(logsAdminWorker)
        .inserer(
            eq("warn"),
            eq("coherence-wikidata"),
            org.mockito.ArgumentMatchers.contains("Q110597455"),
            any());
  }

  @Test
  void revaliderTypes_typeToujoursValide_neModifieRienEtNeJournaliseRien() {
    when(jdbc.queryForList(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(),
            eq(String.class)))
        .thenReturn(List.of("Q537720"));
    when(wikidata.typesInvalides(List.of("Q537720"))).thenReturn(Set.of());

    final int resultat = service.revaliderTypes();

    assertEquals(0, resultat);
    verify(jdbc, never())
        .update(
            org.mockito.ArgumentMatchers.contains("wikidata_id = NULL"),
            org.mockito.ArgumentMatchers.anyMap());
    verify(logsAdminWorker, never()).inserer(any(), any(), any(), any());
  }
}
