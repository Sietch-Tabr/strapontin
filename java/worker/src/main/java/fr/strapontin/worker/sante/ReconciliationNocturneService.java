/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.CorrespondanceArtiste;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.TicketmasterSource;
import fr.strapontin.sources.TicketmasterSource.CandidatVenueTM;
import fr.strapontin.sources.TicketmasterSource.StatutIdTM;
import fr.strapontin.worker.LogsAdminWorker;
import fr.strapontin.worker.enrichissement.EnrichissementArtistesService;
import fr.strapontin.worker.enrichissement.EnrichissementSallesService;
import fr.strapontin.worker.persister.FusionSallesClePorteeService;
import fr.strapontin.worker.persister.ReconciliateurSalles;
import java.util.Arrays;
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

  private static final Logger log = LoggerFactory.getLogger(ReconciliationNocturneService.class);

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
    healthCheckArtistesTM();
    final int nomsIncoherentsTM = healthCheckNomsArtistesTM();
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
   * Vérifie que le nom retourné par TM pour chaque attractionId lié correspond toujours à l'artiste
   * suivi — contrôle distinct du health-check de vivacité ({@link #healthCheckArtistesTM}), qui ne
   * détecte qu'un identifiant mort (HTTP 404), jamais un identifiant vivant mais rattaché à la
   * mauvaise entité (tribute, homonyme). S'applique à <strong>tous</strong> les artistes liés TM,
   * pas seulement les {@code suspect_depuis} : un tribute/homonyme a des événements à venir, il ne
   * déclenche donc jamais le compteur passif {@code nb_zero_consecutifs}.
   *
   * <p>La comparaison passe par {@link CorrespondanceArtiste#estDoublonArtiste} (même algorithme
   * flou que la réconciliation) plutôt qu'une égalité stricte post-normalisation — un écart
   * purement cosmétique (espace, article) n'est jamais signalé. Un écart réel (nom de scène) n'est
   * resignalé que si le nom TM diffère de {@code nom_tm_ignore}, la dernière valeur explicitement
   * acceptée par un admin.
   *
   * @return nombre d'incohérences détectées
   */
  private int healthCheckNomsArtistesTM() {
    final List<Map<String, Object>> lies =
        jdbc.queryForList(
            """
                SELECT asrc.artiste_id, asrc.identifiant_externe, asrc.necessite_intervention_le,
                       asrc.nom_tm_ignore, a.nom
                FROM artistes a JOIN artiste_sources asrc ON asrc.artiste_id = a.id
                WHERE asrc.source = 'ticketmaster'
                """,
            Map.of());

    int incoherences = 0;
    for (var ligne : lies) {
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String attractionId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      final String nomTmIgnore = (String) ligne.get("nom_tm_ignore");
      final boolean dejaSignale = ligne.get("necessite_intervention_le") != null;
      final String nomAttraction = tm.recupererNomAttraction(attractionId);
      if (nomAttraction == null) {
        continue;
      }
      if (CorrespondanceArtiste.estDoublonArtiste(
          Normalisation.normaliserTexte(nom), Normalisation.normaliserTexte(nomAttraction))) {
        continue;
      }
      if (nomAttraction.equals(nomTmIgnore)) {
        continue;
      }
      if (preAccepterSiAliasWikidataConnu(artisteId, nomAttraction)) {
        continue;
      }
      incoherences++;
      signalerNomIncoherent(artisteId, attractionId, nom, nomAttraction, dejaSignale);
    }
    return incoherences;
  }

  /**
   * Pré-accepte automatiquement un nom TM incohérent qui correspond en réalité à un alias Wikidata
   * déjà connu de l'artiste (nom de scène alternatif, ex. « -M- » pour Matthieu Chédid) — même
   * effet que {@code ReferentielArtistesService.ignorerIntervention}, sans attendre une première
   * validation manuelle admin puisque Wikidata fait déjà autorité sur cet alias.
   *
   * @return {@code true} si le nom a été pré-accepté (aucun signalement à faire)
   */
  private boolean preAccepterSiAliasWikidataConnu(final UUID artisteId, final String nomAttraction) {
    final List<String> alias = lireAliasWikidata(artisteId);
    if (alias.isEmpty()) {
      return false;
    }
    final String nomAttractionNorm = Normalisation.normaliserTexte(nomAttraction);
    final boolean correspond =
        alias.stream()
            .anyMatch(
                a ->
                    CorrespondanceArtiste.estDoublonArtiste(
                        Normalisation.normaliserTexte(a), nomAttractionNorm));
    if (!correspond) {
      return false;
    }
    jdbc.update(
        """
        UPDATE artiste_sources
        SET necessite_intervention_le = NULL, nom_tm_ignore = :nomAttraction,
            nom_tm_incoherent = :nomAttraction
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of("artisteId", artisteId.toString(), "nomAttraction", nomAttraction));
    return true;
  }

  private List<String> lireAliasWikidata(final UUID artisteId) {
    final List<String[]> resultats =
        jdbc.query(
            "SELECT alias_wikidata FROM artistes WHERE id = CAST(:id AS uuid)",
            Map.of("id", artisteId.toString()),
            (rs, _) -> {
              final java.sql.Array tableau = rs.getArray("alias_wikidata");
              return tableau != null ? (String[]) tableau.getArray() : new String[0];
            });
    return resultats.isEmpty() ? List.of() : Arrays.asList(resultats.get(0));
  }

  /**
   * Marque l'artiste comme nécessitant une intervention et journalise l'incohérence.
   *
   * <p>Le WARN n'est journalisé qu'à la première détection ({@code dejaSignale} faux) — un état
   * déjà connu et stable (le flag reste posé tant qu'aucun fetch positif cohérent ou résolution
   * manuelle ne l'efface) ne doit pas générer indéfiniment le même WARN à chaque cycle nocturne.
   * {@code nom_tm_incoherent} est en revanche toujours rafraîchi, y compris quand le flag était
   * déjà posé — {@code ignorerIntervention} (module web) doit pouvoir mémoriser le nom TM *courant*
   * comme accepté, pas une valeur obsolète d'une nuit précédente.
   */
  private void signalerNomIncoherent(
      final UUID artisteId, final String attractionId, final String nom, final String nomAttraction, final boolean dejaSignale) {
    jdbc.update(
        """
        UPDATE artiste_sources
        SET necessite_intervention_le = COALESCE(necessite_intervention_le, now()),
            nom_tm_incoherent = :nomAttraction
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of("artisteId", artisteId.toString(), "nomAttraction", nomAttraction));
    if (dejaSignale) {
      return;
    }
    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
        "nom TM incohérent avec l'artiste suivi artiste="
            + nom
            + " nomTM="
            + nomAttraction
            + " attractionId="
            + attractionId,
        Map.of(
            "artisteId", artisteId.toString(),
            "attractionId", attractionId,
            "nomTM", nomAttraction));
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
    for (var ligne : suspectes) {
      final UUID salleId = (UUID) ligne.get("salle_id");
      final String ancienVenueId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      verifiees++;
      if (tm.verifierVenue(ancienVenueId) != StatutIdTM.MORT) {
        reinitialiserSuspect(TypeEntiteSource.SALLE, salleId);
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
            .filter(c -> !dejaUtiliseParAutreEntite(TypeEntiteSource.SALLE, salleId, c.id()))
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
      log.info(
          "reconciliation:nocturne — salle redécouverte nom={} ancien={} nouveau={}",
          nom,
          ancienVenueId,
          nouveauId);
      return 1;
    }

    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
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

  private void healthCheckArtistesTM() {
    final List<Map<String, Object>> suspects =
        jdbc.queryForList(
            """
                SELECT asrc.artiste_id, asrc.identifiant_externe, a.nom
                FROM artiste_sources asrc JOIN artistes a ON a.id = asrc.artiste_id
                WHERE asrc.source = 'ticketmaster' AND asrc.suspect_depuis IS NOT NULL
                """,
            Map.of());

    for (var ligne : suspects) {
      final UUID artisteId = (UUID) ligne.get("artiste_id");
      final String ancienAttractionId = (String) ligne.get("identifiant_externe");
      final String nom = (String) ligne.get("nom");
      if (tm.verifierAttraction(ancienAttractionId) != StatutIdTM.MORT) {
        reinitialiserSuspect(TypeEntiteSource.ARTISTE, artisteId);
        continue;
      }
      redecouvrirArtiste(artisteId, ancienAttractionId, nom);
    }
  }

  /**
   * Le compteur passif ({@code nb_zero_consecutifs}) peut signaler à tort un identifiant suspect
   * alors qu'il s'agit simplement d'une salle/artiste sans événement à venir pour l'instant — la
   * vérification active de cette phase fait autorité et doit effacer ce faux positif dès qu'elle
   * confirme que l'identifiant TM est toujours vivant, plutôt que de laisser le statut "suspect"
   * perdurer jusqu'à expiration (24h, cf. {@link #executer()}).
   */
  private void reinitialiserSuspect(final TypeEntiteSource type, final UUID id) {
    jdbc.update(
        "UPDATE "
            + type.table()
            + " SET nb_zero_consecutifs = 0, suspect_depuis = NULL"
            + " WHERE "
            + type.colonneId()
            + " = CAST(:id AS uuid) AND source = 'ticketmaster'",
        Map.of("id", id.toString()));
  }

  private void redecouvrirArtiste(final UUID artisteId, final String ancienAttractionId, final String nom) {
    final String nouveauId = tm.rechercherAttractionId(nom);
    if (nouveauId != null
        && !nouveauId.equals(ancienAttractionId)
        && !dejaUtiliseParAutreEntite(TypeEntiteSource.ARTISTE, artisteId, nouveauId)) {
      jdbc.update(
          """
          UPDATE artiste_sources
          SET identifiant_externe = :nouveauId, nb_zero_consecutifs = 0, suspect_depuis = NULL
          WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
          """,
          Map.of("nouveauId", nouveauId, "artisteId", artisteId.toString()));
      log.info(
          "reconciliation:nocturne — artiste redécouvert nom={} ancien={} nouveau={}",
          nom,
          ancienAttractionId,
          nouveauId);
      return;
    }
    jdbc.update(
        """
        UPDATE artiste_sources SET necessite_intervention_le = now()
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of("artisteId", artisteId.toString()));
    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
        "redécouverte artiste échouée nom=" + nom + " ancienAttractionId=" + ancienAttractionId,
        Map.of("artisteId", artisteId.toString(), "ancienAttractionId", ancienAttractionId));
  }

  private boolean dejaUtiliseParAutreEntite(
      final TypeEntiteSource type, final UUID idActuel, final String candidatId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT 1 FROM "
                + type.table()
                + " WHERE source = 'ticketmaster' AND identifiant_externe ="
                + " :candidatId AND "
                + type.colonneId()
                + " != CAST(:idActuel AS uuid)",
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
        "warn",
        "reconciliation-nocturne",
        suspectes.size()
            + " slug(s) Digitick suspect(s) — correction manuelle via l'onglet admin Salles",
        Map.of("nbSuspectes", suspectes.size()));
  }
}
