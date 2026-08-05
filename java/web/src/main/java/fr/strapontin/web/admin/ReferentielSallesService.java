/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Gestion du référentiel salles pour l'onglet admin : liste paginée, édition du nom
/// affiché, fusion de doublons.
///
/// L'algorithme de fusion porte en service runtime le gabarit déjà utilisé pour les fusions
/// ad hoc passées ({@code V27__fusionner_doublons_salles_eventim_tm.sql},
/// {@code V28__fusionner_doublon_artiste_krashkarma.sql}) : réassignation des concerts avec
/// dédoublonnage préalable des conflits, puis des abonnements utilisateurs, puis des sources
/// externes, avant suppression de la salle source.
///
/// @since 2.6.0
/// @author Kwaite
@Service
public class ReferentielSallesService {

  private static final int TAILLE_PAGE_DEFAUT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public ReferentielSallesService(
      NamedParameterJdbcTemplate jdbc, LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Liste paginée du référentiel salles, filtrée par nom/ville/dept/statut.
  ///
  /// @param page             numéro de page (0-indexé)
  /// @param nom              filtre nom partiel (insensible à la casse), peut être null
  /// @param ville            filtre ville partielle, peut être null
  /// @param dept             filtre département exact, peut être null
  /// @param statut           filtre {@code "a_jour"}/{@code "a_verifier"}/{@code "invalide"}, peut
  ///     être null
  /// @param wikidataManquant si {@code true}, ne retourne que les salles sans {@code wikidata_id}
  ///     — facilite le repérage des candidats à corriger manuellement sans parcourir toute la
  ///     pagination (une salle avec beaucoup de concerts liés peut être loin dans le tri par
  ///     défaut si elle a déjà un {@code wikidata_id})
  /// @return page de résultats
  public ReponseReferentielSalles lister(
      int page, String nom, String ville, String dept, String statut, boolean wikidataManquant) {
    int pageNum = Math.max(0, page);
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND nom ILIKE :nom ");
      params.put("nom", "%" + nom.strip() + "%");
    }
    if (ville != null && !ville.isBlank()) {
      where.append(" AND ville ILIKE :ville ");
      params.put("ville", "%" + ville.strip() + "%");
    }
    if (dept != null && !dept.isBlank()) {
      where.append(" AND dept = :dept ");
      params.put("dept", dept.strip());
    }
    if (statut != null && !statut.isBlank()) {
      where.append(" AND statut = :statut ");
      params.put("statut", statut.strip());
    }
    if (wikidataManquant) {
      where.append(" AND wikidata_id IS NULL ");
    }

    long total =
        jdbc.queryForObject(
            CTE_SALLES_AGREGEES + "SELECT COUNT(*) FROM salles_agr" + where, params, Long.class);

    Map<String, Object> paramsPage = new HashMap<>(params);
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    List<SalleReferentiel> salles =
        jdbc.query(
            CTE_SALLES_AGREGEES
                + "SELECT * FROM salles_agr"
                + where
                + " ORDER BY nb_concerts DESC, nom ASC LIMIT :taille OFFSET :offset",
            paramsPage,
            (rs, _) ->
                new SalleReferentiel(
                    rs.getObject("id", UUID.class),
                    rs.getString("nom"),
                    rs.getString("nom_affiche"),
                    rs.getString("ville"),
                    rs.getString("dept"),
                    rs.getLong("nb_concerts"),
                    lireSources(rs.getString("sources")),
                    rs.getString("statut"),
                    rs.getString("wikidata_id")));

    int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielSalles(salles, pageNum, totalPages, total);
  }

  private static List<String> lireSources(String sourcesConcat) {
    if (sourcesConcat == null || sourcesConcat.isBlank()) return List.of();
    return Arrays.stream(sourcesConcat.split(",")).toList();
  }

  private static final String CTE_SALLES_AGREGEES =
      """
      WITH salles_agr AS (
        SELECT s.id, s.nom, s.nom_affiche, s.ville, s.dept, s.wikidata_id,
               COUNT(DISTINCT c.id) FILTER (WHERE c.date >= CURRENT_DATE) AS nb_concerts,
               COALESCE(string_agg(DISTINCT ss.source, ','), '') AS sources,
               CASE
                 WHEN MIN(ss.suspect_depuis) IS NULL THEN 'a_jour'
                 WHEN MIN(ss.suspect_depuis) < now() - interval '24 hours' THEN 'invalide'
                 ELSE 'a_verifier'
               END AS statut
        FROM salles s
        LEFT JOIN concerts c ON c.salle_id = s.id
        LEFT JOIN salle_sources ss ON ss.salle_id = s.id
        GROUP BY s.id, s.nom, s.nom_affiche, s.ville, s.dept, s.wikidata_id
      )
      """;

  /// Efface l'enrichissement automatique d'une salle ({@code wikidata_id}, {@code nom_affiche},
  /// {@code ville}) pour la remettre candidate à une nouvelle découverte au prochain cycle
  /// nocturne — nécessaire pour corriger une mauvaise correspondance automatique (ex. homonyme
  /// international, l'enrichissement ne pouvant jamais s'auto-corriger via {@code COALESCE}).
  ///
  /// @param salleId UUID de la salle
  /// @return {@code true} si la salle existe et a été réinitialisée
  public boolean reinitialiserEnrichissement(UUID salleId) {
    int nb =
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

  /// Modifie le nom affiché d'une salle (override manuel prioritaire sur l'enrichissement auto).
  ///
  /// @param salleId    UUID de la salle
  /// @param nomAffiche nouveau nom affiché, ou null pour revenir à l'enrichissement automatique
  /// @return {@code true} si la salle existe et a été mise à jour
  public boolean modifierNomAffiche(UUID salleId, String nomAffiche) {
    String valeur = nomAffiche == null || nomAffiche.isBlank() ? null : nomAffiche.strip();
    Map<String, Object> params = new HashMap<>();
    params.put("nomAffiche", valeur);
    params.put("id", salleId.toString());
    int nb =
        jdbc.update(
            "UPDATE salles SET nom_affiche = :nomAffiche WHERE id = CAST(:id AS uuid)", params);
    return nb > 0;
  }

  /// Fusionne deux salles : la salle source est absorbée par la salle cible (concerts,
  /// abonnements, sources externes réassignés), puis supprimée.
  ///
  /// @param cibleId  UUID de la salle à conserver
  /// @param sourceId UUID de la salle à absorber
  /// @return {@code true} si la fusion a été effectuée, {@code false} si un UUID est inconnu
  @Transactional
  public boolean fusionner(UUID cibleId, UUID sourceId) {
    if (cibleId.equals(sourceId)) return false;
    Long nbExistantes =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM salles WHERE id IN (CAST(:cible AS uuid), CAST(:source AS uuid))",
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()),
            Long.class);
    if (nbExistantes == null || nbExistantes < 2) return false;

    Map<String, Object> ids = Map.of("cible", cibleId.toString(), "source", sourceId.toString());

    // 1. Dédoublonner les concerts en conflit (même artiste/date/heure déjà présent côté cible)
    jdbc.update(
        """
        DELETE FROM concerts c1
        USING concerts c2
        WHERE c1.salle_id = CAST(:source AS uuid)
          AND c2.salle_id = CAST(:cible AS uuid)
          AND c1.artiste_norm = c2.artiste_norm
          AND c1.date = c2.date
          AND c1.heure IS NOT DISTINCT FROM c2.heure
        """,
        ids);

    // 2. Réassigner les concerts restants
    jdbc.update(
        "UPDATE concerts SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);

    // 3. Abonnements en double supprimés, le reste réassigné
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

    // 4. Sources externes réassignées (PK indépendante de salle_id, pas de conflit de PK
    // possible) — dédoublonnage préalable des conflits par type de source (sans lui, deux
    // identifiants du même type, ex. deux ticketmaster, coexistent indéfiniment sur la salle
    // fusionnée).
    dedupliquerSallesSourcesAvantFusion(cibleId, sourceId);
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid) WHERE salle_id = CAST(:source AS"
            + " uuid)",
        ids);

    // 5. Métadonnées récupérées si la cible ne les a pas déjà
    jdbc.update(
        """
        UPDATE salles SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM salles WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM salles WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    // 6. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        "info", "admin", "fusion salle cible=" + cibleId + " source=" + sourceId);
    return true;
  }

  /// Supprime, avant réassignation, les doublons de {@code salle_sources} qu'une fusion créerait
  /// sinon silencieusement : la PK {@code (source, identifiant_externe)} est indépendante
  /// de {@code salle_id}, donc deux lignes portant le même {@code source} (ex. deux identifiants
  /// {@code ticketmaster} différents) peuvent coexister indéfiniment sur la salle fusionnée sans
  /// jamais entrer en conflit de PK. Garde la ligne la plus saine (non suspecte prioritaire, puis
  /// {@code nb_zero_consecutifs} le plus bas) et supprime l'autre.
  ///
  /// @param cibleId  UUID de la salle à conserver
  /// @param sourceId UUID de la salle à absorber
  private void dedupliquerSallesSourcesAvantFusion(UUID cibleId, UUID sourceId) {
    List<Map<String, Object>> conflits =
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
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()));

    for (Map<String, Object> conflit : conflits) {
      boolean garderSource = laSourceEstPlusSaine(conflit);
      Map<String, Object> paramsSuppression = new HashMap<>();
      paramsSuppression.put("source", conflit.get("source"));
      paramsSuppression.put("salleId", garderSource ? cibleId.toString() : sourceId.toString());
      paramsSuppression.put(
          "identifiantExterne", conflit.get(garderSource ? "id_cible" : "id_source"));
      jdbc.update(
          "DELETE FROM salle_sources WHERE salle_id = CAST(:salleId AS uuid) AND source ="
              + " :source AND identifiant_externe = :identifiantExterne",
          paramsSuppression);
    }
  }

  /// Compare la santé de deux lignes {@code salle_sources} (ou {@code artiste_sources}) en
  /// conflit lors d'une fusion : une ligne non suspecte l'emporte toujours sur une ligne suspecte,
  /// à égalité de statut la ligne avec le moins d'échecs consécutifs l'emporte.
  ///
  /// @param conflit ligne agrégée contenant {@code suspect_source}/{@code suspect_cible} et
  ///     {@code sante_source}/{@code sante_cible}
  /// @return {@code true} si la ligne côté source doit être conservée
  static boolean laSourceEstPlusSaine(Map<String, Object> conflit) {
    boolean suspectSource = (boolean) conflit.get("suspect_source");
    boolean suspectCible = (boolean) conflit.get("suspect_cible");
    if (suspectSource != suspectCible) {
      return !suspectSource;
    }
    int santeSource = ((Number) conflit.get("sante_source")).intValue();
    int santeCible = ((Number) conflit.get("sante_cible")).intValue();
    return santeSource <= santeCible;
  }
}
