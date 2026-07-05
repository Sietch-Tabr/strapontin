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

/// Gestion du référentiel artistes pour l'onglet admin (#710) : liste paginée, édition du nom
/// affiché, fusion de doublons.
///
/// <p>La fusion dédoublonne désormais les concerts en conflit avant remapping (#828), à l'image
/// de {@link ReferentielSallesService#fusionner} : même si la contrainte unique
/// {@code uq_concerts_artiste_date_heure_salle} porte sur {@code artiste_norm} (texte, jamais en
/// conflit lors d'un remapping d'{@code artiste_id}), deux artistes distincts fusionnés peuvent
/// chacun avoir enregistré le même concert réel (même salle, même date/heure) sous un
/// {@code artiste_norm} textuel différent selon la source — sans ce nettoyage, les deux lignes
/// subsistaient après fusion, dupliquant le concert sur la fiche de l'artiste cible.
///
/// @since 2.6.0
/// @author Kwaite
@Service
public class ReferentielArtistesService {

  private static final int TAILLE_PAGE_DEFAUT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public ReferentielArtistesService(
      NamedParameterJdbcTemplate jdbc, LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Liste paginée du référentiel artistes, filtrée par nom/statut.
  ///
  /// @param page             numéro de page (0-indexé)
  /// @param nom              filtre nom partiel (insensible à la casse), peut être null
  /// @param statut           filtre {@code "a_jour"}/{@code "a_verifier"}/{@code "invalide"}, peut
  ///     être null
  /// @param wikidataManquant si {@code true}, ne retourne que les artistes sans {@code wikidata_id}
  ///     — facilite le repérage des candidats à corriger manuellement sans parcourir toute la
  ///     pagination
  /// @return page de résultats
  public ReponseReferentielArtistes lister(
      int page, String nom, String statut, boolean wikidataManquant) {
    int pageNum = Math.max(0, page);
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND nom ILIKE :nom ");
      params.put("nom", "%" + nom.strip() + "%");
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
            CTE_ARTISTES_AGREGES + "SELECT COUNT(*) FROM artistes_agr" + where, params, Long.class);

    Map<String, Object> paramsPage = new HashMap<>(params);
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    List<ArtisteReferentiel> artistes =
        jdbc.query(
            CTE_ARTISTES_AGREGES
                + "SELECT * FROM artistes_agr"
                + where
                + " ORDER BY nb_concerts DESC, nom ASC LIMIT :taille OFFSET :offset",
            paramsPage,
            (rs, _) ->
                new ArtisteReferentiel(
                    rs.getObject("id", UUID.class),
                    rs.getString("nom"),
                    rs.getString("nom_affiche"),
                    rs.getLong("nb_concerts"),
                    lireSources(rs.getString("sources")),
                    rs.getString("statut"),
                    rs.getString("wikidata_id"),
                    rs.getString("deezer_id")));

    int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielArtistes(artistes, pageNum, totalPages, total);
  }

  private static List<String> lireSources(String sourcesConcat) {
    if (sourcesConcat == null || sourcesConcat.isBlank()) return List.of();
    return Arrays.stream(sourcesConcat.split(",")).toList();
  }

  private static final String CTE_ARTISTES_AGREGES =
      """
      WITH artistes_agr AS (
        SELECT a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id,
               COUNT(DISTINCT c.id) FILTER (WHERE c.date >= CURRENT_DATE) AS nb_concerts,
               COALESCE(string_agg(DISTINCT asrc.source, ','), '') AS sources,
               CASE
                 WHEN MIN(asrc.suspect_depuis) IS NULL THEN 'a_jour'
                 WHEN MIN(asrc.suspect_depuis) < now() - interval '24 hours' THEN 'invalide'
                 ELSE 'a_verifier'
               END AS statut
        FROM artistes a
        LEFT JOIN concerts c ON c.artiste_id = a.id
        LEFT JOIN artiste_sources asrc ON asrc.artiste_id = a.id
        GROUP BY a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id
      )
      """;

  /// Efface l'enrichissement automatique d'un artiste ({@code wikidata_id}, {@code deezer_id},
  /// {@code nom_affiche}) pour le remettre candidat à une nouvelle découverte au prochain cycle
  /// nocturne — nécessaire pour corriger une mauvaise correspondance automatique, l'enrichissement
  /// ne pouvant jamais s'auto-corriger via {@code COALESCE}.
  ///
  /// @param artisteId UUID de l'artiste
  /// @return {@code true} si l'artiste existe et a été réinitialisé
  public boolean reinitialiserEnrichissement(UUID artisteId) {
    int nb =
        jdbc.update(
            """
            UPDATE artistes SET wikidata_id = NULL, deezer_id = NULL, nom_affiche = NULL
            WHERE id = CAST(:id AS uuid)
            """,
            Map.of("id", artisteId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "enrichissement artiste réinitialisé",
          Map.of("artisteId", artisteId.toString()));
    }
    return nb > 0;
  }

  /// Modifie le nom affiché d'un artiste (override manuel prioritaire sur l'enrichissement auto).
  ///
  /// @param artisteId  UUID de l'artiste
  /// @param nomAffiche nouveau nom affiché, ou null pour revenir à l'enrichissement automatique
  /// @return {@code true} si l'artiste existe et a été mis à jour
  public boolean modifierNomAffiche(UUID artisteId, String nomAffiche) {
    String valeur = nomAffiche == null || nomAffiche.isBlank() ? null : nomAffiche.strip();
    Map<String, Object> params = new HashMap<>();
    params.put("nomAffiche", valeur);
    params.put("id", artisteId.toString());
    int nb =
        jdbc.update(
            "UPDATE artistes SET nom_affiche = :nomAffiche WHERE id = CAST(:id AS uuid)", params);
    return nb > 0;
  }

  /// Fusionne deux artistes : l'artiste source est absorbé par l'artiste cible (concerts,
  /// abonnements, sources externes réassignés), puis supprimé.
  ///
  /// @param cibleId  UUID de l'artiste à conserver
  /// @param sourceId UUID de l'artiste à absorber
  /// @return {@code true} si la fusion a été effectuée, {@code false} si un UUID est inconnu
  @Transactional
  public boolean fusionner(UUID cibleId, UUID sourceId) {
    if (cibleId.equals(sourceId)) return false;
    Long nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM artistes WHERE id IN (CAST(:cible AS uuid), CAST(:source AS"
                + " uuid))",
            Map.of("cible", cibleId.toString(), "source", sourceId.toString()),
            Long.class);
    if (nbExistants == null || nbExistants < 2) return false;

    Map<String, Object> ids = Map.of("cible", cibleId.toString(), "source", sourceId.toString());

    // 1. Dédoublonner les concerts en conflit (même salle/date/heure déjà présent côté cible)
    jdbc.update(
        """
        DELETE FROM concerts c1
        USING concerts c2
        WHERE c1.artiste_id = CAST(:source AS uuid)
          AND c2.artiste_id = CAST(:cible AS uuid)
          AND c1.salle_uuid = c2.salle_uuid
          AND c1.date = c2.date
          AND c1.heure IS NOT DISTINCT FROM c2.heure
        """,
        ids);

    // 2. Réassigner les concerts restants
    jdbc.update(
        "UPDATE concerts SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id = CAST(:source AS"
            + " uuid)",
        ids);

    jdbc.update(
        """
        DELETE FROM user_artistes
        WHERE artiste_id = CAST(:source AS uuid)
          AND EXISTS (
            SELECT 1 FROM user_artistes u2
            WHERE u2.user_id = user_artistes.user_id AND u2.artiste_id = CAST(:cible AS uuid)
          )
        """,
        ids);
    jdbc.update(
        "UPDATE user_artistes SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id = CAST(:source"
            + " AS uuid)",
        ids);

    jdbc.update(
        "UPDATE artiste_sources SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id ="
            + " CAST(:source AS uuid)",
        ids);

    jdbc.update(
        """
        UPDATE artistes SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM artistes WHERE id = CAST(:source AS uuid))),
          deezer_id   = COALESCE(deezer_id, (SELECT deezer_id FROM artistes WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM artistes WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);

    jdbc.update("DELETE FROM artistes WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        "info", "admin", "fusion artiste cible=" + cibleId + " source=" + sourceId);
    return true;
  }
}
