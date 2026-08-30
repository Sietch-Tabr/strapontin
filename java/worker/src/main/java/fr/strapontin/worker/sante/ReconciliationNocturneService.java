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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Orchestrateur du job nocturne de réconciliation des sources salles/artistes.
///
/// Phase 1 — health-check des identifiants TM suspects (`suspect_depuis IS NOT NULL`) et
/// redécouverte active si l'identifiant est mort. Digitick : alerte seule (pas de redécouverte
/// auto, slugs gérés manuellement). Eventim : ignoré (IDs synthétiques, pas de health-check
/// pertinent).
///
/// Phase 2 — délègue à {@link EnrichissementSallesService} et {@link
/// EnrichissementArtistesService}.
///
/// La redécouverte passive décrite dans le ticket (scanner les concerts des dernières 24h) n'est
/// pas implémentée : le pipeline temps réel existant ({@code
/// ReconciliateurSalles.upsertSalleSource}, exécuté à chaque fetch) réalise déjà cette association
/// par correspondance de nom, et {@code concerts} ne conserve pas l'identifiant brut une fois
/// résolu — un scan nocturne dédié ne découvrirait rien de plus (décision actée pour le jalon
/// 2.6.0).
///
/// @since 2.6.0
/// @author Kwaite
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

  public ReconciliationNocturneService(
      NamedParameterJdbcTemplate jdbc,
      TicketmasterSource tm,
      LogsAdminWorker logsAdminWorker,
      EnrichissementSallesService enrichissementSalles,
      EnrichissementArtistesService enrichissementArtistes,
      CoherenceWikidataSalleService coherenceWikidataSalle,
      ReconciliateurSalles reconciliateurSalles,
      FusionSallesClePorteeService fusionSallesClePortee) {
    this.jdbc = jdbc;
    this.tm = tm;
    this.logsAdminWorker = logsAdminWorker;
    this.enrichissementSalles = enrichissementSalles;
    this.enrichissementArtistes = enrichissementArtistes;
    this.coherenceWikidataSalle = coherenceWikidataSalle;
    this.reconciliateurSalles = reconciliateurSalles;
    this.fusionSallesClePortee = fusionSallesClePortee;
  }

  /// Exécute les deux phases du job nocturne.
  ///
  /// <p>L'enrichissement artistes (Wikidata/Deezer) est traité avant l'enrichissement salles
  /// (Overpass/Wikidata) : l'instance publique Overpass est une infrastructure partagée
  /// régulièrement surchargée (timeouts fréquents en 2026), chaque lot en échec consommant tout son
  /// timeout client avant d'abandonner. Ce risque de ralentissement ne doit pas retarder
  /// l'enrichissement artistes, indépendant d'Overpass.
  ///
  /// @return bilan agrégé de l'exécution
  public ResultatReconciliation executer() {
    int[] bilanSalles = healthCheckSallesTM();
    healthCheckArtistesTM();
    int nomsIncoherentsTM = healthCheckNomsArtistesTM();
    alerterDigitickSuspect();

    int enrichisArtistes = enrichissementArtistes.enrichir();
    int enrichisSalles = enrichissementSalles.enrichir();
    int incoherencesWikidata = coherenceWikidataSalle.verifier();
    int typesWikidataInvalides = coherenceWikidataSalle.revaliderTypes();
    int sallesOrphelinesResolues = reconciliateurSalles.retenterSallesOrphelines();
    int sallesFusionnees = fusionSallesClePortee.fusionner();

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
        sallesFusionnees);
  }

  /// Vérifie que le nom retourné par TM pour chaque attractionId lié correspond toujours à
  /// l'artiste suivi — contrôle distinct du health-check de vivacité ({@link
  /// #healthCheckArtistesTM}), qui ne détecte qu'un identifiant mort (HTTP 404), jamais un
  /// identifiant vivant mais rattaché à la mauvaise entité (tribute, homonyme). S'applique à
  /// **tous** les artistes liés TM, pas seulement les `suspect_depuis` : un tribute/homonyme a des
  /// événements à venir, il ne déclenche donc jamais le compteur passif `nb_zero_consecutifs`.
  ///
  /// <p>La comparaison passe par {@link CorrespondanceArtiste#estDoublonArtiste} (même algorithme
  /// flou que la réconciliation) plutôt qu'une égalité stricte post-normalisation — un écart
  /// purement cosmétique (espace, article) n'est jamais signalé. Un écart réel (nom de scène) n'est
  /// resignalé que si le nom TM diffère de {@code nom_tm_ignore}, la dernière valeur explicitement
  /// acceptée par un admin.
  ///
  /// @return nombre d'incohérences détectées
  private int healthCheckNomsArtistesTM() {
    List<Map<String, Object>> lies =
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
      UUID artisteId = (UUID) ligne.get("artiste_id");
      String attractionId = (String) ligne.get("identifiant_externe");
      String nom = (String) ligne.get("nom");
      String nomTmIgnore = (String) ligne.get("nom_tm_ignore");
      boolean dejaSignale = ligne.get("necessite_intervention_le") != null;
      String nomAttraction = tm.recupererNomAttraction(attractionId);
      if (nomAttraction == null) continue;
      if (CorrespondanceArtiste.estDoublonArtiste(
          Normalisation.normaliserTexte(nom), Normalisation.normaliserTexte(nomAttraction))) {
        continue;
      }
      if (nomAttraction.equals(nomTmIgnore)) continue;
      incoherences++;
      signalerNomIncoherent(artisteId, attractionId, nom, nomAttraction, dejaSignale);
    }
    return incoherences;
  }

  /// Marque l'artiste comme nécessitant une intervention et journalise l'incohérence.
  ///
  /// <p>Le WARN n'est journalisé qu'à la première détection ({@code dejaSignale} faux) — un état
  /// déjà connu et stable (le flag reste posé tant qu'aucun fetch positif cohérent ou résolution
  /// manuelle ne l'efface) ne doit pas générer indéfiniment le même WARN à chaque cycle nocturne.
  /// {@code nom_tm_incoherent} est en revanche toujours rafraîchi, y compris quand le flag était
  /// déjà posé — {@code ignorerIntervention} (module web) doit pouvoir mémoriser le nom TM
  /// *courant* comme accepté, pas une valeur obsolète d'une nuit précédente.
  private void signalerNomIncoherent(
      UUID artisteId, String attractionId, String nom, String nomAttraction, boolean dejaSignale) {
    jdbc.update(
        """
        UPDATE artiste_sources
        SET necessite_intervention_le = COALESCE(necessite_intervention_le, now()),
            nom_tm_incoherent = :nomAttraction
        WHERE artiste_id = CAST(:artisteId AS uuid) AND source = 'ticketmaster'
        """,
        Map.of("artisteId", artisteId.toString(), "nomAttraction", nomAttraction));
    if (dejaSignale) return;
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

  /// @return tableau {@code [verifiees, redecouvertes, ambigues]}
  private int[] healthCheckSallesTM() {
    List<Map<String, Object>> suspectes =
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
      UUID salleId = (UUID) ligne.get("salle_id");
      String ancienVenueId = (String) ligne.get("identifiant_externe");
      String nom = (String) ligne.get("nom");
      verifiees++;
      if (tm.verifierVenue(ancienVenueId) != StatutIdTM.MORT) {
        reinitialiserSuspect("salle_sources", "salle_id", salleId);
        continue;
      }

      int resultatRedecouverte = redecouvrirSalle(salleId, ancienVenueId, nom);
      if (resultatRedecouverte > 0) redecouvertes++;
      else if (resultatRedecouverte < 0) ambigues++;
    }
    return new int[] {verifiees, redecouvertes, ambigues};
  }

  /// @return 1 si redécouverte automatique effectuée, -1 si ambigu (0 ou ≥2 candidats), 0 sinon
  private int redecouvrirSalle(UUID salleId, String ancienVenueId, String nom) {
    List<CandidatVenueTM> candidats = tm.rechercherVenuesParNom(nom);
    List<CandidatVenueTM> valides =
        candidats.stream()
            .filter(c -> !c.id().equals(ancienVenueId))
            .filter(c -> tm.compterEventsVenue(c.id()) > 0)
            .filter(c -> !dejaUtiliseParAutreEntite("salle_sources", "salle_id", salleId, c.id()))
            .toList();

    if (valides.size() == 1) {
      String nouveauId = valides.get(0).id();
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
    List<Map<String, Object>> suspects =
        jdbc.queryForList(
            """
            SELECT asrc.artiste_id, asrc.identifiant_externe, a.nom
            FROM artiste_sources asrc JOIN artistes a ON a.id = asrc.artiste_id
            WHERE asrc.source = 'ticketmaster' AND asrc.suspect_depuis IS NOT NULL
            """,
            Map.of());

    for (var ligne : suspects) {
      UUID artisteId = (UUID) ligne.get("artiste_id");
      String ancienAttractionId = (String) ligne.get("identifiant_externe");
      String nom = (String) ligne.get("nom");
      if (tm.verifierAttraction(ancienAttractionId) != StatutIdTM.MORT) {
        reinitialiserSuspect("artiste_sources", "artiste_id", artisteId);
        continue;
      }
      redecouvrirArtiste(artisteId, ancienAttractionId, nom);
    }
  }

  /// Le compteur passif ({@code nb_zero_consecutifs}) peut signaler à tort un identifiant suspect
  /// alors qu'il s'agit simplement d'une salle/artiste sans événement à venir pour l'instant — la
  /// vérification active de cette phase fait autorité et doit effacer ce faux positif dès qu'elle
  /// confirme que l'identifiant TM est toujours vivant, plutôt que de laisser le statut "suspect"
  /// perdurer jusqu'à expiration (24h, cf. {@link #executer()}).
  private void reinitialiserSuspect(String table, String colonneId, UUID id) {
    jdbc.update(
        "UPDATE "
            + table
            + " SET nb_zero_consecutifs = 0, suspect_depuis = NULL"
            + " WHERE "
            + colonneId
            + " = CAST(:id AS uuid) AND source = 'ticketmaster'",
        Map.of("id", id.toString()));
  }

  private void redecouvrirArtiste(UUID artisteId, String ancienAttractionId, String nom) {
    String nouveauId = tm.rechercherAttractionId(nom);
    if (nouveauId != null
        && !nouveauId.equals(ancienAttractionId)
        && !dejaUtiliseParAutreEntite("artiste_sources", "artiste_id", artisteId, nouveauId)) {
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
      String table, String colonneId, UUID idActuel, String candidatId) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT 1 FROM "
                + table
                + " WHERE source = 'ticketmaster' AND identifiant_externe ="
                + " :candidatId AND "
                + colonneId
                + " != CAST(:idActuel AS uuid)",
            Map.of("candidatId", candidatId, "idActuel", idActuel.toString()));
    return !lignes.isEmpty();
  }

  private void alerterDigitickSuspect() {
    List<Map<String, Object>> suspectes =
        jdbc.queryForList(
            """
            SELECT ss.identifiant_externe, s.nom
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'digitick' AND ss.suspect_depuis IS NOT NULL
            """,
            Map.of());
    if (suspectes.isEmpty()) return;

    logsAdminWorker.inserer(
        "warn",
        "reconciliation-nocturne",
        suspectes.size()
            + " slug(s) Digitick suspect(s) — correction manuelle via l'onglet admin Salles",
        Map.of("nbSuspectes", suspectes.size()));
  }
}
