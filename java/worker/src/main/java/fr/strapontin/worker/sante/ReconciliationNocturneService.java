/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.worker.enrichissement.EnrichissementArtistesService;
import fr.strapontin.worker.enrichissement.EnrichissementSallesService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.persister.ReconciliateurSalles;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur du job nocturne de réconciliation des sources salles/artistes.
 *
 * <p>Phase 1 — health-check des identifiants TM suspects ({@code suspect_depuis IS NOT NULL}) et
 * redécouverte active si l'identifiant est mort. Digitick : alerte seule (pas de redécouverte auto,
 * slugs gérés manuellement). Eventim : ignoré (IDs synthétiques, pas de health-check pertinent).
 *
 * <p>Phase 2 — délègue à {@link EnrichissementSallesService} et {@link
 * EnrichissementArtistesService}.
 *
 * <p>La redécouverte passive décrite dans le ticket (scanner les concerts des dernières 24h) n'est
 * pas implémentée : le pipeline temps réel existant ({@code
 * ReconciliateurSalles.upsertSalleSource}, exécuté à chaque fetch) réalise déjà cette association
 * par correspondance de nom, et {@code concerts} ne conserve pas l'identifiant brut une fois résolu
 * — un scan nocturne dédié ne découvrirait rien de plus (décision actée pour le jalon 2.6.0).
 *
 * @since 2.6.0
 * @author Kwaite
 */
// 10 collaborateurs indépendants : chacun est une vérification/un
// enrichissement autonome du job nocturne, sans regroupement thématique naturel
// (contrairement à FetchRapportage côté fetch) — un tel regroupement serait un
// simple contournement du seuil, pas une amélioration de conception.
// HealthCheckArtistesTM/HealthCheckSallesTM (déjà des beans Spring, voir leur
// propre Javadoc) ont réduit le compte de 11 à 10.
@SuppressWarnings("PMD.ExcessiveParameterList")
@Service
public class ReconciliationNocturneService {

  private final EnrichissementSallesService enrichissementSalles;
  private final EnrichissementArtistesService enrichissementArtistes;
  private final CoherenceWikidataSalleService coherenceWikidataSalle;
  private final ReconciliateurSalles reconciliateurSalles;
  private final FusionSallesClePorteeService fusionSallesClePortee;
  private final CoherenceMultiSalleService coherenceMultiSalle;
  private final SuggestionMotsGenericsService suggestionMotsGenerics;
  private final ControleIntegriteService controleIntegrite;
  private final HealthCheckArtistesTM healthCheckArtistesTM;
  private final HealthCheckSallesTM healthCheckSallesTM;

  /**
   * @param enrichissementSalles enrichissement Wikidata/OSM des salles (phase 2)
   * @param enrichissementArtistes enrichissement Wikidata/Deezer des artistes (phase 2)
   * @param coherenceWikidataSalle revalidation ville/wikidata_id des salles déjà enrichies
   * @param reconciliateurSalles réconciliation des noms de salles intra-lot/inter-jobs
   * @param fusionSallesClePortee fusion nocturne des salles détectées comme doublons
   * @param coherenceMultiSalle détection de contamination/doublon multi-salles
   * @param suggestionMotsGenerics suggestion de mots-types de salle génériques non couverts
   * @param controleIntegrite contrôles d'intégrité généralistes du référentiel
   * @param healthCheckArtistesTM health-check et redécouverte des identifiants TM artiste suspects
   * @param healthCheckSallesTM health-check et redécouverte des identifiants TM salle suspects
   */
  public ReconciliationNocturneService(
      final EnrichissementSallesService enrichissementSalles,
      final EnrichissementArtistesService enrichissementArtistes,
      final CoherenceWikidataSalleService coherenceWikidataSalle,
      final ReconciliateurSalles reconciliateurSalles,
      final FusionSallesClePorteeService fusionSallesClePortee,
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
    this.coherenceMultiSalle = coherenceMultiSalle;
    this.suggestionMotsGenerics = suggestionMotsGenerics;
    this.controleIntegrite = controleIntegrite;
    this.healthCheckArtistesTM = healthCheckArtistesTM;
    this.healthCheckSallesTM = healthCheckSallesTM;
  }

  /**
   * Exécute les deux phases du job nocturne.
   *
   * <p>L'enrichissement artistes (Wikidata/Deezer) est traité avant l'enrichissement salles
   * (Overpass/Wikidata) : l'instance publique Overpass est une infrastructure partagée
   * régulièrement surchargée (timeouts fréquents en 2026), chaque lot en échec consommant tout son
   * timeout client avant d'abandonner. Ce risque de ralentissement ne doit pas retarder
   * l'enrichissement artistes, indépendant d'Overpass.
   *
   * @return bilan agrégé de l'exécution
   */
  public ResultatReconciliation executer() {
    final int[] bilanSalles = healthCheckSallesTM.verifier();
    healthCheckArtistesTM.verifierVivacite();
    final int nomsIncoherentsTM = healthCheckArtistesTM.verifierNoms();
    healthCheckSallesTM.alerterDigitickSuspect();

    final int enrichisArtistes = enrichissementArtistes.enrichir();
    final int enrichisSalles = enrichissementSalles.enrichir();
    final int incoherencesWikidata = coherenceWikidataSalle.verifier();
    final int typesWikidataInvalides = coherenceWikidataSalle.revaliderTypes();
    final int sallesOrphelinesResolues = reconciliateurSalles.retenterSallesOrphelines();
    final int sallesFusionnees = fusionSallesClePortee.fusionner();
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
        contaminationsSignalees,
        doublonsSignales,
        motsGenericsSuggeres,
        integriteSignalements);
  }
}
