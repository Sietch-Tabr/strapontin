/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.SalleHierarchie;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Détecte et fusionne en cycle nocturne des paires de salles désignant le
 * même bâtiment (clé forte + {@link CorrespondanceSalle#estDoublonSalle}),
 * complétant {@link ReconciliateurSalles} (détails : wiki Sources-de-données).
 *
 * @since 2.8.4
 * @author Kwaite
 */
@Service
public class FusionSallesClePorteeService {

  private static final Logger LOG = LoggerFactory.getLogger(FusionSallesClePorteeService.class);

  // Distance au-delà de laquelle deux salles ne peuvent structurellement pas
  // être le même bâtiment, même en cas de coïncidence artiste+date+heure+dept
  // et de nom similaire — un complexe multi-salles (La Seine Musicale, #1180)
  // peut partager un nom quasi identique entre deux halls physiquement
  // distincts de plusieurs centaines de mètres.
  private static final double DISTANCE_MAX_FUSION_METRES = 500;
  private static final String CLE_CIBLE = "cible";
  private static final int NB_SALLES_PAIRE = 2;
  private static final String CLE_SOURCE = "source";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  private record PaireSalles(UUID idA, UUID idB) {}

  /**
   * @param jdbc accès JDBC
   * @param logsAdminWorker journalisation de chaque fusion effectuée
   */
  public FusionSallesClePorteeService(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Détecte puis fusionne les paires de salles partageant un concert à
   * venir identique — une paire déjà disparue (fusionnée plus tôt dans le
   * même cycle) est ignorée silencieusement (redondante).
   * @return nombre de fusions effectuées
   */
  public int fusionner() {
    final List<PaireSalles> paires = detecterPaires();
    final Set<UUID> dejaAbsorbees = new HashSet<>();
    int fusions = 0;
    for (final PaireSalles paire : paires) {
      if (dejaAbsorbees.contains(paire.idA()) || dejaAbsorbees.contains(paire.idB())) {
        continue;
      }
      final UUID[] cibleSource = choisirCibleEtSource(paire.idA(), paire.idB());
      if (cibleSource == null) {
        continue;
      }
      fusionnerUnePaire(cibleSource[0], cibleSource[1]);
      dejaAbsorbees.add(cibleSource[1]);
      fusions++;
    }
    return fusions;
  }

  /**
   * Repère les paires de salle_id distincts partageant exactement même
   * artiste/date/heure/dept (clé forte, appliquée rétroactivement), filtrées
   * par estDoublonSalle sur les noms — un seul concert coïncidant par erreur
   * ne doit jamais suffire à fusionner (voir le rationale de classe).
   */
  private List<PaireSalles> detecterPaires() {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT DISTINCT c1.salle_id AS salle_a, c2.salle_id AS salle_b,
                   sa.nom_norm AS nom_a, sb.nom_norm AS nom_b,
                   sa.latitude AS lat_a, sa.longitude AS lon_a,
                   sb.latitude AS lat_b, sb.longitude AS lon_b
            FROM concerts c1
            JOIN concerts c2
              ON c1.artiste_norm = c2.artiste_norm
             AND c1.date = c2.date
             AND c1.heure = c2.heure
             AND c1.dept = c2.dept
             AND c1.salle_id < c2.salle_id
            JOIN salles sa ON sa.id = c1.salle_id
            JOIN salles sb ON sb.id = c2.salle_id
            WHERE c1.salle_id IS NOT NULL AND c2.salle_id IS NOT NULL
              AND c1.heure IS NOT NULL AND c1.dept IS NOT NULL
              AND c1.date >= CURRENT_DATE
            """,
            Map.of());
    final List<PaireSalles> paires = new ArrayList<>();
    for (final Map<String, Object> ligne : lignes) {
      final String nomA = (String) ligne.get("nom_a");
      final String nomB = (String) ligne.get("nom_b");
      if (!CorrespondanceSalle.estDoublonSalle(nomA, nomB)) {
        continue;
      }
      if (distanceExcessive(ligne)) {
        continue;
      }
      paires.add(new PaireSalles((UUID) ligne.get("salle_a"), (UUID) ligne.get("salle_b")));
    }
    return paires;
  }

  /**
   * @return {@code true} si les deux salles ont toutes deux des coordonnées
   *     connues et que leur distance dépasse
   *     {@value #DISTANCE_MAX_FUSION_METRES} m — {@code false} si l'une des
   *     deux coordonnées est inconnue (comportement actuel nom+dept inchangé
   *     dans ce cas).
   */
  private static boolean distanceExcessive(final Map<String, Object> ligne) {
    final Double latA = (Double) ligne.get("lat_a");
    final Double lonA = (Double) ligne.get("lon_a");
    final Double latB = (Double) ligne.get("lat_b");
    final Double lonB = (Double) ligne.get("lon_b");
    if (latA == null || lonA == null || latB == null || lonB == null) {
      return false;
    }
    return DistanceGeo.metres(latA, lonA, latB, lonB) > DISTANCE_MAX_FUSION_METRES;
  }

  /**
   * Vérifie que deux salles ont des coordonnées connues et proches, seul
   * signal positif suffisant pour une fusion automatique (contrairement à
   * distanceExcessive, permissif si une coordonnée manque).
   * @return {@code true} si coordonnées connues et à moins de
   *     {@value #DISTANCE_MAX_FUSION_METRES} m l'une de l'autre
   */
  public static boolean coordonneesConnuesEtProches(
      final Double latA, final Double lonA, final Double latB, final Double lonB) {
    return DistanceGeo.connuesEtProches(latA, lonA, latB, lonB, DISTANCE_MAX_FUSION_METRES);
  }

  /**
   * Choisit la salle à conserver (cible) entre les deux membres de la paire
   * : priorité au wikidata_id déjà connu (donnée coûteuse à reconstituer),
   * puis au nombre de concerts déjà rattachés, puis à l'UUID le plus petit
   * pour un résultat déterministe entre deux cycles.
   * @return [cible, source], ou {@code null} si l'une des deux n'existe plus
   */
  // Le tableau représente une paire cible/source, pas une collection à agréger
  // — l'appelant teste explicitement null pour distinguer "salle disparue" d'un
  // tableau vide, sémantiques différentes.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private UUID[] choisirCibleEtSource(final UUID idA, final UUID idB) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT s.id AS id, s.wikidata_id AS wikidata_id,
                   (SELECT COUNT(*) FROM concerts c WHERE c.salle_id = s.id) AS nb_concerts
            FROM salles s WHERE s.id IN (CAST(:a AS uuid), CAST(:b AS uuid))
            """,
            Map.of("a", idA.toString(), "b", idB.toString()));
    if (lignes.size() < NB_SALLES_PAIRE) {
      return null;
    }

    final Map<String, Object> la =
        lignes.get(0).get("id").equals(idA) ? lignes.get(0) : lignes.get(1);
    final Map<String, Object> lb =
        lignes.get(0).get("id").equals(idA) ? lignes.get(1) : lignes.get(0);

    final boolean aWikidata = la.get("wikidata_id") != null;
    final boolean bWikidata = lb.get("wikidata_id") != null;
    if (aWikidata != bWikidata) {
      return aWikidata ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    final long nbA = ((Number) la.get("nb_concerts")).longValue();
    final long nbB = ((Number) lb.get("nb_concerts")).longValue();
    if (nbA != nbB) {
      return nbA > nbB ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
    }

    return idA.compareTo(idB) <= 0 ? new UUID[] {idA, idB} : new UUID[] {idB, idA};
  }

  /**
   * Fusionne deux salles établies (réassigne concerts/abonnements/sources/
   * sous-salles, récupère les métadonnées manquantes, supprime la source).
   * Public : réutilisé par EnrichissementSallesService (collision wikidata_id).
   * @param cibleId salle à conserver
   * @param sourceId salle à absorber puis supprimer
   */
  @Transactional
  public void fusionnerUnePaire(final UUID cibleId, final UUID sourceId) {
    final Map<String, Object> ids =
        Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString());

    fusionnerEvenements(ids);
    fusionnerAbonnementsEtSources(ids, cibleId, sourceId);
    fusionnerMetadonneesEtSousSalles(ids, cibleId, sourceId);

    // 7. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    LOG.info(
        "reconciliation:nocturne — salles fusionnées par clé forte cible={} source={}",
        cibleId,
        sourceId);
    // Fusion automatique et destructive (suppression d'une salle établie) :
    // jamais silencieuse, contrairement au reste du cycle nocturne qui ne fait
    // que journaliser un constat.
    logsAdminWorker.inserer(
        "info",
        "reconciliation-nocturne",
        "salles fusionnées automatiquement par clé forte (artiste+date+heure+dept) cible="
            + cibleId
            + " source="
            + sourceId,
        Map.of("salleCible", cibleId.toString(), "salleSource", sourceId.toString()));
  }

  // 1. Événements en conflit (même artiste/date/heure déjà présent côté cible)
  // : leurs observations sont reportées sur l'événement cible plutôt que
  // perdues (#1192) — celles qui entreraient en conflit sur (evenement_id,
  // source, url) sont supprimées, déjà représentées côté cible. L'événement
  // source, vidé de ses observations, est ensuite supprimé. 2. Réassigner les
  // événements restants (ceux sans doublon côté cible).
  private void fusionnerEvenements(final Map<String, Object> ids) {
    jdbc.update(
        """
        DELETE FROM observations o1
        USING observations o2, evenements e1, evenements e2
        WHERE o1.evenement_id = e1.id AND o2.evenement_id = e2.id
          AND e1.salle_id = CAST(:source AS uuid) AND e2.salle_id = CAST(:cible AS uuid)
          AND e1.artiste_norm = e2.artiste_norm AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
          AND o1.source = o2.source AND o1.url IS NOT DISTINCT FROM o2.url
        """,
        ids);
    jdbc.update(
        """
        UPDATE observations SET evenement_id = e2.id
        FROM evenements e1, evenements e2
        WHERE observations.evenement_id = e1.id
          AND e1.salle_id = CAST(:source AS uuid) AND e2.salle_id = CAST(:cible AS uuid)
          AND e1.artiste_norm = e2.artiste_norm AND e1.date = e2.date
          AND e1.heure IS NOT DISTINCT FROM e2.heure
        """,
        ids);
    jdbc.update(
        """
        DELETE FROM evenements
        WHERE salle_id = CAST(:source AS uuid)
          AND NOT EXISTS (SELECT 1 FROM observations WHERE observations.evenement_id = evenements.id)
        """,
        ids);
    jdbc.update(
        "UPDATE evenements SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);
  }

  // 3. Abonnements en double supprimés, le reste réassigné. 4. Sources externes
  // réassignées, dédoublonnage préalable des conflits par type de source.
  private void fusionnerAbonnementsEtSources(
      final Map<String, Object> ids, final UUID cibleId, final UUID sourceId) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE salle_id = CAST(:source AS uuid)
          AND EXISTS (
            SELECT 1 FROM user_salles u2
            WHERE u2.user_id = user_salles.user_id AND u2.salle_id = CAST(:cible AS uuid)
          )
        """,
        ids);
    jdbc.update(
        "UPDATE user_salles SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);

    dedupliquerSallesSourcesAvantFusion(cibleId, sourceId);
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source"
            + " AS uuid)",
        ids);
  }

  // 5. Métadonnées récupérées si la cible ne les a pas déjà. 6. Sous-salles de
  // la source réassignées à la cible — sinon la suppression de la source
  // orpheline leurs salle_parent_id (violation de contrainte FK). Si la cible
  // était elle-même une sous-salle de la source (cas limite : un enfant
  // l'emporte sur son propre complexe par nombre de concerts), elle hérite du
  // parent de la source plutôt que de rester rattachée à une salle sur le point
  // d'être supprimée.
  private void fusionnerMetadonneesEtSousSalles(
      final Map<String, Object> ids, final UUID cibleId, final UUID sourceId) {
    jdbc.update(
        """
        UPDATE salles SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM salles WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM salles WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    jdbc.update(
        "UPDATE salles SET salle_parent_id ="
            + " (SELECT salle_parent_id FROM salles WHERE id = CAST(:source AS uuid))"
            + " WHERE id = CAST(:cible AS uuid) AND salle_parent_id = CAST(:source AS uuid)",
        ids);
    reassignerSousSallesSansCycle(cibleId, sourceId);
  }

  /**
   * Réassigne à la cible chaque sous-salle de la source (hors cible
   * elle-même, déjà traitée), sauf celle dont la cible est déjà un
   * descendant — la réassigner créerait un cycle enfant → cible → ... →
   * enfant (#1201). Une telle sous-salle est détachée (parent à NULL)
   * plutôt que laissée pointer vers la source supprimée, et signalée pour
   * revue manuelle (cas limite non exclu par le seul cas direct géré).
   */
  private void reassignerSousSallesSansCycle(final UUID cibleId, final UUID sourceId) {
    final List<UUID> enfants =
        jdbc.query(
            "SELECT id FROM salles WHERE salle_parent_id = CAST(:source AS uuid) AND id !="
                + " CAST(:cible AS uuid)",
            Map.of(CLE_SOURCE, sourceId.toString(), CLE_CIBLE, cibleId.toString()),
            (rs, _) -> rs.getObject("id", UUID.class));

    for (final UUID enfant : enfants) {
      if (SalleHierarchie.creeraitCycle(cibleId, enfant, this::lireSalleParentId)) {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = NULL WHERE id = CAST(:id AS uuid)",
            Map.of("id", enfant.toString()));
        logsAdminWorker.inserer(
            "warn",
            "reconciliation-nocturne",
            "cycle salle_parent_id évité lors d'une fusion — sous-salle détachée enfant="
                + enfant
                + " cible="
                + cibleId,
            Map.of("enfant", enfant.toString(), CLE_CIBLE, cibleId.toString()));
      } else {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = CAST(:cible AS uuid) WHERE id = CAST(:id AS"
                + " uuid)",
            Map.of(CLE_CIBLE, cibleId.toString(), "id", enfant.toString()));
      }
    }
  }

  // findFirst() sur un flux contenant un unique élément null lève une
  // NullPointerException (Optional.of appelé en interne) — un accès direct à la
  // liste évite ce piège pour une salle racine sans parent.
  private UUID lireSalleParentId(final UUID salleId) {
    final List<UUID> resultat =
        jdbc.query(
            "SELECT salle_parent_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            (rs, _) -> (UUID) rs.getObject("salle_parent_id"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }

  private void dedupliquerSallesSourcesAvantFusion(final UUID cibleId, final UUID sourceId) {
    final List<Map<String, Object>> conflits =
        jdbc.queryForList(
            """
            SELECT src.source AS source,
                   src.identifiant_externe AS id_source,
                   dst.identifiant_externe AS id_cible,
                   src.nb_zero_consecutifs AS sante_source,
                   dst.nb_zero_consecutifs AS sante_cible,
                   (src.suspect_depuis IS NOT NULL) AS suspect_source,
                   (dst.suspect_depuis IS NOT NULL) AS suspect_cible
            FROM salle_sources src
            JOIN salle_sources dst ON dst.source = src.source AND dst.salle_id = CAST(:cible AS uuid)
            WHERE src.salle_id = CAST(:source AS uuid)
            """,
            Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString()));

    for (final Map<String, Object> conflit : conflits) {
      final boolean garderSource = laSourceEstPlusSaine(conflit);
      final Map<String, Object> paramsSuppression = new HashMap<>();
      paramsSuppression.put(CLE_SOURCE, conflit.get(CLE_SOURCE));
      paramsSuppression.put("salleId", garderSource ? cibleId.toString() : sourceId.toString());
      paramsSuppression.put(
          "identifiantExterne", conflit.get(garderSource ? "id_cible" : "id_source"));
      jdbc.update(
          "DELETE FROM salle_sources WHERE salle_id = CAST(:salleId AS uuid) AND source ="
              + " :source AND identifiant_externe = :identifiantExterne",
          paramsSuppression);
    }
  }

  /**
   * Même comparateur que {@code ReferentielSallesService.laSourceEstPlusSaine}
   * (module web) : une ligne non suspecte l'emporte toujours sur une ligne
   * suspecte, à égalité de statut la ligne avec le moins d'échecs consécutifs
   * l'emporte.
   *
   * @return {@code true} si la ligne côté source doit être conservée
   */
  private static boolean laSourceEstPlusSaine(final Map<String, Object> conflit) {
    final boolean suspectSource = Boolean.TRUE.equals(conflit.get("suspect_source"));
    final boolean suspectCible = Boolean.TRUE.equals(conflit.get("suspect_cible"));
    if (suspectSource != suspectCible) {
      return !suspectSource;
    }
    final long santeSource = ((Number) conflit.get("sante_source")).longValue();
    final long santeCible = ((Number) conflit.get("sante_cible")).longValue();
    return santeSource <= santeCible;
  }
}
