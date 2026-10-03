/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.worker.enrichissement.EnrichissementArtistesService;
import fr.strapontin.worker.enrichissement.EnrichissementSallesService;
import fr.strapontin.worker.persister.FusionEvenementsArtisteService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.persister.ReconciliateurSalles;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur du job nocturne de réconciliation des sources
 * salles/artistes — 2 phases (health-check/redécouverte IDs TM,
 * enrichissement Wikidata/OSM/Deezer), détail : CLAUDE.md,
 * Santé/référentiel admin.
 * @since 2.6.0
 * @author Kwaite
 */
// 11 collaborateurs indépendants : chacun est une vérification/un
// enrichissement autonome du job nocturne, sans regroupement thématique naturel
// (contrairement à FetchRapportage côté fetch) — un tel regroupement serait un
// simple contournement du seuil, pas une amélioration de conception.
// HealthCheckArtistesTM/HealthCheckSallesTM (déjà des beans Spring, voir leur
// propre Javadoc) ont réduit le compte de 12 à 11.
@SuppressWarnings("PMD.ExcessiveParameterList")
@Service
public class ReconciliationNocturneService {

  private final EnrichissementSallesService enrichissementSalles;
  private final EnrichissementArtistesService enrichissementArtistes;
  private final CoherenceWikidataSalleService coherenceWikidataSalle;
  private final ReconciliateurSalles reconciliateurSalles;
  private final FusionSallesClePorteeService fusionSallesClePortee;
  private final FusionEvenementsArtisteService fusionEvenementsArtiste;
  private final CoherenceMultiSalleService coherenceMultiSalle;
  private final SuggestionMotsGenericsService suggestionMotsGenerics;
  private final ControleIntegriteService controleIntegrite;
  private final HealthCheckArtistesTM healthCheckArtistesTM;
  private final HealthCheckSallesTM healthCheckSallesTM;

  /**
   * Assemble les 11 collaborateurs autonomes du job nocturne (santé,
   * enrichissement, réconciliation, intégrité — voir Javadoc de classe).
   */
  public ReconciliationNocturneService(
      final EnrichissementSallesService enrichissementSalles,
      final EnrichissementArtistesService enrichissementArtistes,
      final CoherenceWikidataSalleService coherenceWikidataSalle,
      final ReconciliateurSalles reconciliateurSalles,
      final FusionSallesClePorteeService fusionSallesClePortee,
      final FusionEvenementsArtisteService fusionEvenementsArtiste,
      final CoherenceMultiSalleService coherenceMultiSalle,
      final SuggestionMotsGenericsService suggestionMotsGenerics,
      final ControleIntegriteService controleIntegrite,
      final HealthCheckArtistesTM healthCheckArtistesTM,
      final HealthCheckSallesTM healthCheckSallesTM) {
    this.enrichissementSalles = enrichissementSalles;
    this.enrichissementArtistes = enrichissementArtistes;
    this.coherenceWikidataSalle = coherenceWikidataSalle;
    this.reconciliateurSalles = reconciliateurSalles;
    this.fusionSallesClePortee = fusionSallesClePortee;
    this.fusionEvenementsArtiste = fusionEvenementsArtiste;
    this.coherenceMultiSalle = coherenceMultiSalle;
    this.suggestionMotsGenerics = suggestionMotsGenerics;
    this.controleIntegrite = controleIntegrite;
    this.healthCheckArtistesTM = healthCheckArtistesTM;
    this.healthCheckSallesTM = healthCheckSallesTM;
  }

  /**
   * Exécute les deux phases du job nocturne — artistes avant salles
   * (Overpass, infrastructure partagée, timeouts fréquents, ne doit
   * pas retarder un enrichissement indépendant).
   * @return bilan agrégé de l'exécution
   */
  public ResultatReconciliation executer() {
    final int[] bilanSalles = healthCheckSallesTM.verifier();
    healthCheckSallesTM.rattraperPositions();
    healthCheckArtistesTM.verifierVivacite();
    final int nomsIncoherentsTM = healthCheckArtistesTM.verifierNoms();
    healthCheckSallesTM.alerterLieuxEventimSuspects();

    final int enrichisArtistes = enrichissementArtistes.enrichir();
    final int enrichisSalles = enrichissementSalles.enrichir();
    final int incoherencesWikidata = coherenceWikidataSalle.verifier();
    final int typesWikidataInvalides = coherenceWikidataSalle.revaliderTypes();
    final int sallesOrphelinesResolues = reconciliateurSalles.retenterSallesOrphelines();
    // Avant la fusion par clé forte : une fiche périmée sur la mauvaise salle
    // fournirait la coïncidence qui fusionne deux salles distinctes.
    final int fichesPerimeesFusionnees = fusionEvenementsArtiste.fusionnerFichesPerimees();
    final int sallesFusionnees = fusionSallesClePortee.fusionner();
    final int evenementsFusionnes = fichesPerimeesFusionnees + fusionEvenementsArtiste.fusionner();
    final int contaminationsSignalees = coherenceMultiSalle.detecterContamination();
    final int doublonsSignales = coherenceMultiSalle.detecterDoublons();
    final int motsGenericsSuggeres = suggestionMotsGenerics.suggerer();
    final int integriteSignalements = controleIntegrite.verifier();

    return new ResultatReconciliation(
        bilanSalles[0],
        bilanSalles[1],
        bilanSalles[2],
        enrichisSalles,
        enrichisArtistes,
        incoherencesWikidata,
        typesWikidataInvalides,
        nomsIncoherentsTM,
        sallesOrphelinesResolues,
        sallesFusionnees,
        evenementsFusionnes,
        contaminationsSignalees,
        doublonsSignales,
        motsGenericsSuggeres,
        integriteSignalements);
  }
}
