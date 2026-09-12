/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.SalleHierarchie;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion du référentiel salles pour l'onglet admin : liste paginée, édition du nom affiché, fusion
 * de doublons.
 *
 * <p>L'algorithme de fusion porte en service runtime le gabarit déjà utilisé pour les fusions ad
 * hoc passées ({@code V27__fusionner_doublons_salles_eventim_tm.sql}, {@code
 * V28__fusionner_doublon_artiste_krashkarma.sql}) : réassignation des concerts avec dédoublonnage
 * préalable des conflits, puis des abonnements utilisateurs, puis des sources externes, avant
 * suppression de la salle source.
 *
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class ReferentielSallesService {

  private static final int TAILLE_PAGE_DEFAUT = 50;
  private static final String CLE_CIBLE = "cible";
  private static final String CLE_SOURCE = "source";

  private static final String CTE_SALLES_AGREGEES =
      """
      WITH salles_agr AS (
        SELECT s.id, s.nom, s.nom_affiche, s.ville, s.dept, s.wikidata_id, p.nom AS nom_parent,
               COUNT(DISTINCT c.id) FILTER (WHERE c.date >= CURRENT_DATE) AS nb_concerts,
               COALESCE(string_agg(DISTINCT ss.source, ','), '') AS sources,
               CASE
                 WHEN MIN(ss.suspect_depuis) IS NULL THEN 'a_jour'
                 WHEN MIN(ss.suspect_depuis) < now() - interval '24 hours' THEN 'invalide'
                 ELSE 'a_verifier'
               END AS statut
        FROM salles s
        LEFT JOIN salles p ON p.id = s.salle_parent_id
        LEFT JOIN concerts c ON c.salle_id = s.id
        LEFT JOIN salle_sources ss ON ss.salle_id = s.id
        GROUP BY s.id, s.nom, s.nom_affiche, s.ville, s.dept, s.wikidata_id, p.nom
      )
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param logAdminService journalisation des fusions effectuées
   */
  public ReferentielSallesService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste paginée du référentiel salles, filtrée par nom/ville/dept/statut.
   *
   * @param page numéro de page (0-indexé)
   * @param nom filtre nom partiel (insensible à la casse), peut être null
   * @param ville filtre ville partielle, peut être null
   * @param dept filtre département exact, peut être null
   * @param statut filtre {@code "a_jour"}/{@code "a_verifier"}/{@code "invalide"}, peut être null
   * @param wikidataManquant si {@code true}, ne retourne que les salles sans {@code wikidata_id} —
   *     facilite le repérage des candidats à corriger manuellement sans parcourir toute la
   *     pagination (une salle avec beaucoup de concerts liés peut être loin dans le tri par défaut
   *     si elle a déjà un {@code wikidata_id})
   * @return page de résultats
   */
  public ReponseReferentielSalles lister(
      final int page,
      final String nom,
      final String ville,
      final String dept,
      final String statut,
      final boolean wikidataManquant) {
    final int pageNum = Math.max(0, page);
    final FiltresSalles filtres =
        construireFiltresSalles(nom, ville, dept, statut, wikidataManquant);

    final Map<String, Object> paramsPage = new HashMap<>(filtres.params());
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    final PageSalles resultatPage = chargerPageSalles(filtres.where(), paramsPage);

    long total = resultatPage.total();
    // page vide (offset au-delà du total) : total_filtre n'est renvoyé par aucune ligne, un
    // second aller-retour reste alors nécessaire pour connaître le total réel.
    if (resultatPage.salles().isEmpty() && pageNum > 0) {
      total = compterSallesFiltrees(filtres.where(), filtres.params());
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielSalles(resultatPage.salles(), pageNum, totalPages, total);
  }

  private record FiltresSalles(StringBuilder where, Map<String, Object> params) {}

  private FiltresSalles construireFiltresSalles(
      final String nom,
      final String ville,
      final String dept,
      final String statut,
      final boolean wikidataManquant) {
    final StringBuilder where = new StringBuilder(60).append(" WHERE 1=1 ");
    final Map<String, Object> params = new HashMap<>();
    ajouterFiltreLike(where, params, "nom", nom);
    ajouterFiltreLike(where, params, "ville", ville);
    ajouterFiltreExact(where, params, "dept", dept);
    ajouterFiltreExact(where, params, "statut", statut);
    if (wikidataManquant) {
      where.append(" AND wikidata_id IS NULL ");
    }
    return new FiltresSalles(where, params);
  }

  private static void ajouterFiltreLike(
      final StringBuilder where,
      final Map<String, Object> params,
      final String cle,
      final String valeur) {
    if (valeur != null && !valeur.isBlank()) {
      where.append(" AND ").append(cle).append(" ILIKE :").append(cle).append(' ');
      params.put(cle, "%" + valeur.strip() + "%");
    }
  }

  private static void ajouterFiltreExact(
      final StringBuilder where,
      final Map<String, Object> params,
      final String cle,
      final String valeur) {
    if (valeur != null && !valeur.isBlank()) {
      where.append(" AND ").append(cle).append(" = :").append(cle).append(' ');
      params.put(cle, valeur.strip());
    }
  }

  private record PageSalles(List<SalleReferentiel> salles, long total) {}

  private PageSalles chargerPageSalles(
      final StringBuilder where, final Map<String, Object> paramsPage) {
    final long[] total = {0};
    final List<SalleReferentiel> salles =
        jdbc.query(
            CTE_SALLES_AGREGEES
                + "SELECT id, nom, nom_affiche, ville, dept, wikidata_id, nb_concerts, sources,"
                + " statut, nom_parent, COUNT(*) OVER() AS total_filtre FROM salles_agr"
                + where
                + " ORDER BY nb_concerts DESC, nom ASC LIMIT :taille OFFSET :offset",
            paramsPage,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new SalleReferentiel(
                  rs.getObject("id", UUID.class),
                  rs.getString("nom"),
                  rs.getString("nom_affiche"),
                  rs.getString("ville"),
                  rs.getString("dept"),
                  rs.getLong("nb_concerts"),
                  lireSources(rs.getString("sources")),
                  rs.getString("statut"),
                  rs.getString("wikidata_id"),
                  rs.getString("nom_parent"));
            });
    return new PageSalles(salles, total[0]);
  }

  private long compterSallesFiltrees(final StringBuilder where, final Map<String, Object> params) {
    final Long totalVide =
        jdbc.queryForObject(
            CTE_SALLES_AGREGEES + "SELECT COUNT(*) FROM salles_agr" + where, params, Long.class);
    return totalVide != null ? totalVide : 0;
  }

  private static List<String> lireSources(final String sourcesConcat) {
    if (sourcesConcat == null || sourcesConcat.isBlank()) {
      return List.of();
    }
    return Arrays.stream(sourcesConcat.split(",")).toList();
  }

  /**
   * Efface l'enrichissement automatique d'une salle ({@code wikidata_id}, {@code nom_affiche},
   * {@code ville}) pour la remettre candidate à une nouvelle découverte au prochain cycle nocturne
   * — nécessaire pour corriger une mauvaise correspondance automatique (ex. homonyme international,
   * l'enrichissement ne pouvant jamais s'auto-corriger via {@code COALESCE}).
   *
   * @param salleId UUID de la salle
   * @return {@code true} si la salle existe et a été réinitialisée
   */
  public boolean reinitialiserEnrichissement(final UUID salleId) {
    final int nb =
        jdbc.update(
            """
            UPDATE salles SET wikidata_id = NULL, nom_affiche = NULL, ville = NULL
            WHERE id = CAST(:id AS uuid)
            """,
            Map.of("id", salleId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "enrichissement salle réinitialisé",
          Map.of("salleId", salleId.toString()));
    }
    return nb > 0;
  }

  /**
   * Modifie le nom affiché d'une salle (override manuel prioritaire sur l'enrichissement auto).
   *
   * @param salleId UUID de la salle
   * @param nomAffiche nouveau nom affiché, ou null pour revenir à l'enrichissement automatique
   * @return {@code true} si la salle existe et a été mise à jour
   */
  public boolean modifierNomAffiche(final UUID salleId, final String nomAffiche) {
    final String valeur = nomAffiche == null || nomAffiche.isBlank() ? null : nomAffiche.strip();
    final Map<String, Object> params = new HashMap<>();
    params.put("nomAffiche", valeur);
    params.put("id", salleId.toString());
    final int nb =
        jdbc.update(
            "UPDATE salles SET nom_affiche = :nomAffiche WHERE id = CAST(:id AS uuid)", params);
    return nb > 0;
  }

  /**
   * Fusionne deux salles : la salle source est absorbée par la salle cible (concerts, abonnements,
   * sources externes réassignés), puis supprimée.
   *
   * @param cibleId UUID de la salle à conserver
   * @param sourceId UUID de la salle à absorber
   * @return {@code true} si la fusion a été effectuée, {@code false} si un UUID est inconnu
   */
  @Transactional
  public boolean fusionner(final UUID cibleId, final UUID sourceId) {
    if (cibleId.equals(sourceId)) {
      return false;
    }
    final Long nbExistantes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE id IN (CAST(:cible AS uuid), CAST(:source AS uuid))",
            Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString()),
            Long.class);
    if (nbExistantes == null || nbExistantes < 2) {
      return false;
    }

    final Map<String, Object> ids =
        Map.of(CLE_CIBLE, cibleId.toString(), CLE_SOURCE, sourceId.toString());

    fusionnerEvenements(ids);
    fusionnerAbonnementsEtSources(ids, cibleId, sourceId);
    fusionnerMetadonneesEtSousSalles(ids, cibleId, sourceId);

    // 7. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        "info", "admin", "fusion salle cible=" + cibleId + " source=" + sourceId);
    return true;
  }

  // 1. Événements en conflit (même artiste/date/heure déjà présent côté cible) : leurs
  // observations sont reportées sur l'événement cible plutôt que perdues (#1192) — celles qui
  // entreraient en conflit sur (evenement_id, source, url) sont supprimées, déjà représentées
  // côté cible. L'événement source, vidé de ses observations, est ensuite supprimé.
  // 2. Réassigner les événements restants (ceux sans doublon côté cible).
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

  // 3. Abonnements en double supprimés, le reste réassigné.
  // 4. Sources externes réassignées (PK indépendante de salle_id, pas de conflit de PK
  // possible) — dédoublonnage préalable des conflits par type de source (sans lui, deux
  // identifiants du même type, ex. deux ticketmaster, coexistent indéfiniment sur la salle
  // fusionnée).
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
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);
  }

  // 5. Métadonnées récupérées si la cible ne les a pas déjà.
  // 6. Sous-salles de la source réassignées à la cible — sinon la suppression de la source
  // orpheline leurs salle_parent_id (violation de contrainte FK). Si la cible était elle-même
  // une sous-salle de la source, elle hérite du parent de la source plutôt que de rester
  // rattachée à une salle sur le point d'être supprimée.
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
   * Réassigne à la cible chaque sous-salle de la source (hors cible elle-même, déjà traitée), sauf
   * celle dont la cible est déjà un descendant — la réassigner créerait un cycle {@code enfant →
   * cible → ... → enfant} (#1201). Une telle sous-salle est détachée (parent à {@code NULL}) plutôt
   * que laissée pointer vers la source sur le point d'être supprimée, et signalée pour revue
   * manuelle.
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
        logAdminService.logAdmin(
            "warn",
            "admin",
            "cycle salle_parent_id évité lors d'une fusion — sous-salle détachée enfant="
                + enfant
                + " cible="
                + cibleId);
      } else {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = CAST(:cible AS uuid) WHERE id = CAST(:id AS"
                + " uuid)",
            Map.of(CLE_CIBLE, cibleId.toString(), "id", enfant.toString()));
      }
    }
  }

  // findFirst() sur un flux contenant un unique élément null lève une NullPointerException
  // (Optional.of appelé en interne) — un accès direct à la liste évite ce piège pour une salle
  // racine sans parent.
  private UUID lireSalleParentId(final UUID salleId) {
    final List<UUID> resultat =
        jdbc.query(
            "SELECT salle_parent_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            (rs, _) -> (UUID) rs.getObject("salle_parent_id"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }

  /**
   * Supprime, avant réassignation, les doublons de {@code salle_sources} qu'une fusion créerait
   * sinon silencieusement : la PK {@code (source, identifiant_externe)} est indépendante de {@code
   * salle_id}, donc deux lignes portant le même {@code source} (ex. deux identifiants {@code
   * ticketmaster} différents) peuvent coexister indéfiniment sur la salle fusionnée sans jamais
   * entrer en conflit de PK. Garde la ligne la plus saine (non suspecte prioritaire, puis {@code
   * nb_zero_consecutifs} le plus bas) et supprime l'autre.
   *
   * @param cibleId UUID de la salle à conserver
   * @param sourceId UUID de la salle à absorber
   */
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
      final boolean garderSource = FusionSourcesUtils.laSourceEstPlusSaine(conflit);
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
}
