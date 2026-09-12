/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.enrichissement.EnrichissementArtistesService;
import fr.strapontin.worker.enrichissement.EnrichissementSallesService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.persister.ReconciliateurSalles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
@Service
public class ReconciliationNocturneService {

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliationNocturneService.class);
  private static final String NIVEAU_WARN = "warn";
  private static final String SOURCE_RECONCILIATION_NOCTURNE = "reconciliation-nocturne";

  private final NamedParameterJdbcTemplate jdbc;
  private final TicketmasterSource tm;
  private final LogsAdminWorker logsAdminWorker;
  private final EnrichissementSallesService enrichissementSalles;
  private final EnrichissementArtistesService enrichissementArtistes;
  private final CoherenceWikidataSalleService coherenceWikidataSalle;
  private final ReconciliateurSalles reconciliateurSalles;
  private final FusionSallesClePorteeService fusionSallesClePortee;
  private final CoherenceMultiSalleService coherenceMultiSalle;
  private final SuggestionMotsGenericsService suggestionMotsGenerics;
  private final ControleIntegriteService controleIntegrite;
  private final HealthCheckArtistesTM healthCheckArtistesTM;

  /**
   * @param jdbc accès JDBC
   * @param tm health-check et redécouverte des identifiants TM suspects
   * @param logsAdminWorker journalisation du bilan de cycle
   * @param enrichissementSalles enrichissement Wikidata/OSM des salles (phase 2)
   * @param enrichissementArtistes enrichissement Wikidata/Deezer des artistes (phase 2)
   * @param coherenceWikidataSalle revalidation ville/wikidata_id des salles déjà enrichies
   * @param reconciliateurSalles réconciliation des noms de salles intra-lot/inter-jobs
   * @param fusionSallesClePortee fusion nocturne des salles détectées comme doublons
   * @param coherenceMultiSalle détection de contamination/doublon multi-salles
   * @param suggestionMotsGenerics suggestion de mots-types de salle génériques non couverts
   * @param controleIntegrite contrôles d'intégrité généralistes du référentiel
   */
  public ReconciliationNocturneService(
      final NamedParameterJdbcTemplate jdbc,
      final TicketmasterSource tm,
      final LogsAdminWorker logsAdminWorker,
      final EnrichissementSallesService enrichissementSalles,
      final EnrichissementArtistesService enrichissementArtistes,
      final CoherenceWikidataSalleService coherenceWikidataSalle,
      final ReconciliateurSalles reconciliateurSalles,
      final FusionSallesClePorteeService fusionSallesClePortee,
      final CoherenceMultiSalleService coherenceMultiSalle,
      final SuggestionMotsGenericsService suggestionMotsGenerics,
      final ControleIntegriteService controleIntegrite) {
    this.jdbc = jdbc;
    this.tm = tm;
    this.logsAdminWorker = logsAdminWorker;
    this.enrichissementSalles = enrichissementSalles;
    this.enrichissementArtistes = enrichissementArtistes;
    this.coherenceWikidataSalle = coherenceWikidataSalle;
    this.reconciliateurSalles = reconciliateurSalles;
    this.fusionSallesClePortee = fusionSallesClePortee;
    this.coherenceMultiSalle = coherenceMultiSalle;
    this.suggestionMotsGenerics = suggestionMotsGenerics;
    this.controleIntegrite = controleIntegrite;
    this.healthCheckArtistesTM = new HealthCheckArtistesTM(jdbc, tm, logsAdminWorker);
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
    final int[] bilanSalles = healthCheckSallesTM();
    healthCheckArtistesTM.verifierVivacite();
    final int nomsIncoherentsTM = healthCheckArtistesTM.verifierNoms();
    alerterDigitickSuspect();

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

  /**
   * @return tableau {@code [verifiees, redecouvertes, ambigues]}
   */
  private int[] healthCheckSallesTM() {
    final List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.salle_id, ss.identifiant_externe, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'ticketmaster' AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());

    int verifiees = 0;
    int redecouvertes = 0;
    int ambigues = 0;
    for (final Map<String, Object> ligne : suspectes) {
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String ancienVenueId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      verifiees++;
      if (tm.verifierVenue(ancienVenueId) != StatutIdTM.MORT) {
        reinitialiserSuspect(salleId);
        continue;
      }

      final int resultatRedecouverte = redecouvrirSalle(salleId, ancienVenueId, nom);
      if (resultatRedecouverte > 0) {
        redecouvertes++;
      } else if (resultatRedecouverte < 0) {
        ambigues++;
      }
    }
    return new int[] {verifiees, redecouvertes, ambigues};
  }

  /**
   * @return 1 si redécouverte automatique effectuée, -1 si ambigu (0 ou ≥2 candidats), 0 sinon
   */
  private int redecouvrirSalle(final UUID salleId, final String ancienVenueId, final String nom) {
    final List<CandidatVenueTM> candidats = tm.rechercherVenuesParNom(nom);
    final List<CandidatVenueTM> valides =
        candidats.stream()
            .filter(c -> !c.id().equals(ancienVenueId))
            .filter(c -> tm.compterEventsVenue(c.id()) > 0)
            .filter(c -> !dejaUtiliseParAutreEntite(salleId, c.id()))
            .toList();

    if (valides.size() == 1) {
      final String nouveauId = valides.get(0).id();
      jdbc.update(
          """
          UPDATE salle_sources
          SET identifiant_externe = :nouveauId, nb_zero_consecutifs = 0, suspect_depuis = NULL
          WHERE salle_id = CAST(:salleId AS uuid) AND source = 'ticketmaster'
          """,
          Map.of("nouveauId", nouveauId, "salleId", salleId.toString()));
      LOG.info(
          "reconciliation:nocturne — salle redécouverte nom={} ancien={} nouveau={}",
          nom,
          ancienVenueId,
          nouveauId);
      return 1;
    }

    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        "redécouverte ambiguë salle="
            + nom
            + " ancienVenueId="
            + ancienVenueId
            + " candidats="
            + valides.size(),
        Map.of(
            "salleId",
            salleId.toString(),
            "ancienVenueId",
            ancienVenueId,
            "nbCandidats",
            valides.size()));
    return -1;
  }

  /**
   * Le compteur passif ({@code nb_zero_consecutifs}) peut signaler à tort une salle suspecte alors
   * qu'il s'agit simplement d'une salle sans événement à venir pour l'instant — la vérification
   * active de cette phase fait autorité et doit effacer ce faux positif dès qu'elle confirme que
   * l'identifiant TM est toujours vivant, plutôt que de laisser le statut "suspect" perdurer
   * jusqu'à expiration (24h, cf. {@link #executer()}).
   */
  private void reinitialiserSuspect(final UUID salleId) {
    jdbc.update(
        "UPDATE salle_sources SET nb_zero_consecutifs = 0, suspect_depuis = NULL"
            + " WHERE salle_id = CAST(:id AS uuid) AND source = 'ticketmaster'",
        Map.of("id", salleId.toString()));
  }

  private boolean dejaUtiliseParAutreEntite(final UUID idActuel, final String candidatId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT 1 FROM salle_sources WHERE source = 'ticketmaster' AND identifiant_externe ="
                + " :candidatId AND salle_id != CAST(:idActuel AS uuid)",
            Map.of("candidatId", candidatId, "idActuel", idActuel.toString()));
    return !lignes.isEmpty();
  }

  private void alerterDigitickSuspect() {
    final List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.identifiant_externe, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'digitick' AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());
    if (suspectes.isEmpty()) {
      return;
    }

    logsAdminWorker.inserer(
        NIVEAU_WARN,
        SOURCE_RECONCILIATION_NOCTURNE,
        suspectes.size()
            + " slug(s) Digitick suspect(s) — correction manuelle via l'onglet admin Salles",
        Map.of("nbSuspectes", suspectes.size()));
  }
}
