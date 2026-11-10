/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.FusionEvenementsSql;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion du référentiel artistes pour l'onglet admin : liste paginée,
 * édition du nom affiché, fusion de doublons (préserve les observations,
 * statut a_jour/invalide — CLAUDE.md, Modèle événement canonique).
 *
 * @since 2.6.0
 * @author Kwaite
 */
@Service
public class ReferentielArtistesService {

  private static final int TAILLE_PAGE_DEFAUT = 50;
  private static final String NIVEAU_INFO = "info";
  private static final String SOURCE_ADMIN = "admin";
  private static final String CLE_ARTISTE_ID = "artisteId";
  private static final String CLE_SOURCE = "source";

  private static final String CTE_ARTISTES_AGREGES =
      """
      WITH artistes_agr AS (
        SELECT a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id,
               COUNT(DISTINCT c.id) FILTER (WHERE c.date >= CURRENT_DATE) AS nb_concerts,
               COALESCE(string_agg(DISTINCT asrc.source, ','), '') AS sources,
               CASE
                 WHEN MIN(asrc.necessite_intervention_le) IS NULL THEN 'a_jour'
                 ELSE 'invalide'
               END AS statut,
               MAX(CASE WHEN asrc.source = 'ticketmaster' THEN asrc.identifiant_externe END)
                 AS tm_identifiant_externe,
               MIN(asrc.necessite_intervention_le) AS intervention_depuis
        FROM artistes a
        LEFT JOIN concerts c ON c.artiste_id = a.id
        LEFT JOIN artiste_sources asrc ON asrc.artiste_id = a.id
        GROUP BY a.id, a.nom, a.nom_affiche, a.wikidata_id, a.deezer_id
      )
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param logAdminService journalisation des fusions effectuées
   */
  public ReferentielArtistesService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste paginée du référentiel artistes, filtrée par nom/statut.
   * @param page numéro de page (0-indexé)
   * @param nom filtre nom partiel (insensible à la casse), peut être null
   * @param statut filtre {@code "a_jour"}/{@code "invalide"}, peut être null
   * @param wikidataManquant filtre les artistes sans {@code wikidata_id}
   * @return page de résultats
   */
  public ReponseReferentielArtistes lister(
      final int page, final String nom, final String statut, final boolean wikidataManquant) {
    final int pageNum = Math.max(0, page);
    final FiltresArtistes filtres = construireFiltresArtistes(nom, statut, wikidataManquant);

    final Map<String, Object> paramsPage = new HashMap<>(filtres.params());
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    final PageArtistes resultatPage = chargerPageArtistes(filtres.where(), paramsPage);

    long total = resultatPage.total();
    // page vide (offset au-delà du total) : total_filtre n'est renvoyé par
    // aucune ligne, un second aller-retour reste alors nécessaire pour
    // connaître le total réel.
    if (resultatPage.artistes().isEmpty() && pageNum > 0) {
      total = compterArtistesFiltres(filtres.where(), filtres.params());
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielArtistes(resultatPage.artistes(), pageNum, totalPages, total);
  }

  private record FiltresArtistes(StringBuilder where, Map<String, Object> params) {}

  private FiltresArtistes construireFiltresArtistes(
      final String nom, final String statut, final boolean wikidataManquant) {
    final StringBuilder where = new StringBuilder(100).append(" WHERE 1=1 ");
    final Map<String, Object> params = new HashMap<>();
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
    return new FiltresArtistes(where, params);
  }

  private record PageArtistes(List<ArtisteReferentiel> artistes, long total) {}

  private PageArtistes chargerPageArtistes(
      final StringBuilder where, final Map<String, Object> paramsPage) {
    final long[] total = {0};
    final List<ArtisteReferentiel> artistes =
        jdbc.query(
            CTE_ARTISTES_AGREGES
                + "SELECT id, nom, nom_affiche, wikidata_id, deezer_id, nb_concerts, sources,"
                + " statut, tm_identifiant_externe, intervention_depuis, COUNT(*) OVER() AS total_filtre FROM"
                + " artistes_agr"
                + where
                + " ORDER BY nb_concerts DESC, nom ASC LIMIT :taille OFFSET :offset",
            paramsPage,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new ArtisteReferentiel(
                  rs.getObject("id", UUID.class),
                  rs.getString("nom"),
                  rs.getString("nom_affiche"),
                  rs.getLong("nb_concerts"),
                  lireSources(rs.getString("sources")),
                  rs.getString("statut"),
                  rs.getString("wikidata_id"),
                  rs.getString("deezer_id"),
                  rs.getString("tm_identifiant_externe"),
                  Optional.ofNullable(rs.getObject("intervention_depuis", OffsetDateTime.class))
                      .map(OffsetDateTime::toInstant)
                      .orElse(null));
            });
    return new PageArtistes(artistes, total[0]);
  }

  private long compterArtistesFiltres(final StringBuilder where, final Map<String, Object> params) {
    final Long totalVide =
        jdbc.queryForObject(
            CTE_ARTISTES_AGREGES + "SELECT COUNT(*) FROM artistes_agr" + where, params, Long.class);
    return totalVide != null ? totalVide : 0;
  }

  private static List<String> lireSources(final String sourcesConcat) {
    if (sourcesConcat == null || sourcesConcat.isBlank()) {
      return List.of();
    }
    return Arrays.stream(sourcesConcat.split(",")).toList();
  }

  /**
   * Efface l'enrichissement auto d'un artiste ({@code wikidata_id}/{@code
   * deezer_id}/{@code nom_affiche}) pour le remettre candidat à une nouvelle
   * découverte (corrige une mauvaise correspondance, jamais auto-corrigée
   * via {@code COALESCE}).
   * @param artisteId UUID de l'artiste
   * @return {@code true} si l'artiste existe et a été réinitialisé
   */
  public boolean reinitialiserEnrichissement(final UUID artisteId) {
    final int nb =
        jdbc.update(
            """
            UPDATE artistes SET wikidata_id = NULL, deezer_id = NULL, nom_affiche = NULL
            WHERE id = CAST(:id AS uuid)
            """,
            Map.of("id", artisteId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          NIVEAU_INFO,
          SOURCE_ADMIN,
          "enrichissement artiste réinitialisé",
          Map.of(CLE_ARTISTE_ID, artisteId.toString()));
    }
    return nb > 0;
  }

  /**
   * Efface {@code necessite_intervention_le} sans fusion ni changement
   * d'identifiant (divergence de nom TM jugée acceptable par un admin) —
   * détail : CLAUDE.md, Santé/référentiel admin.
   * @param artisteId UUID de l'artiste
   * @return {@code true} si au moins une ligne a été mise à jour
   */
  public boolean ignorerIntervention(final UUID artisteId) {
    final int nb =
        jdbc.update(
            """
            UPDATE artiste_sources
            SET necessite_intervention_le = NULL, nom_tm_ignore = nom_tm_incoherent
            WHERE artiste_id = CAST(:id AS uuid)
            """,
            Map.of("id", artisteId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          NIVEAU_INFO,
          SOURCE_ADMIN,
          "intervention ignorée artiste=" + artisteId,
          Map.of(CLE_ARTISTE_ID, artisteId.toString()));
    }
    return nb > 0;
  }

  /**
   * Corrige directement l'identifiant Ticketmaster d'un artiste (alternative
   * à la fusion) — réinitialise le compteur de santé, efface {@code
   * necessite_intervention_le} ; conflit PK remonte en 409.
   * @param artisteId UUID de l'artiste
   * @param identifiantExterne nouvel attractionId Ticketmaster (non vide)
   * @return {@code true} si l'artiste a une source ticketmaster mise à jour
   */
  public boolean corrigerIdentifiantTm(final UUID artisteId, final String identifiantExterne) {
    final int nb =
        jdbc.update(
            """
            UPDATE artiste_sources
            SET identifiant_externe = :identifiant, nb_zero_consecutifs = 0,
                suspect_depuis = NULL, necessite_intervention_le = NULL
            WHERE artiste_id = CAST(:id AS uuid) AND source = 'ticketmaster'
            """,
            Map.of("id", artisteId.toString(), "identifiant", identifiantExterne.strip()));
    if (nb > 0) {
      logAdminService.logAdmin(
          NIVEAU_INFO,
          SOURCE_ADMIN,
          "identifiant TM corrigé artiste=" + artisteId,
          Map.of(CLE_ARTISTE_ID, artisteId.toString(), "identifiantExterne", identifiantExterne));
    }
    return nb > 0;
  }

  /**
   * Modifie le nom affiché d'un artiste (override manuel prioritaire sur
   * l'enrichissement auto).
   * @param artisteId UUID de l'artiste
   * @param nomAffiche nouveau nom affiché, ou null pour revenir à
   *     l'enrichissement automatique
   * @return {@code true} si l'artiste existe et a été mis à jour
   */
  public boolean modifierNomAffiche(final UUID artisteId, final String nomAffiche) {
    final String valeur = nomAffiche == null || nomAffiche.isBlank() ? null : nomAffiche.strip();
    final Map<String, Object> params = new HashMap<>();
    params.put("nomAffiche", valeur);
    params.put("id", artisteId.toString());
    final int nb =
        jdbc.update(
            "UPDATE artistes SET nom_affiche = :nomAffiche WHERE id = CAST(:id AS uuid)", params);
    return nb > 0;
  }

  /**
   * Fusionne deux artistes : l'artiste source est absorbé par l'artiste
   * cible (concerts, plateaux de soirées, abonnements, sources externes),
   * puis supprimé.
   * @param cibleId UUID de l'artiste à conserver
   * @param sourceId UUID de l'artiste à absorber
   * @return {@code true} si fusionnée, {@code false} si UUID inconnu
   */
  @Transactional
  public boolean fusionner(final UUID cibleId, final UUID sourceId) {
    if (cibleId.equals(sourceId)) {
      return false;
    }
    final Long nbExistants =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM artistes WHERE id IN (CAST(:cible AS uuid), CAST(:source AS"
                + " uuid))",
            Map.of("cible", cibleId.toString(), CLE_SOURCE, sourceId.toString()),
            Long.class);
    if (nbExistants == null || nbExistants < 2) {
      return false;
    }

    final Map<String, Object> ids =
        Map.of("cible", cibleId.toString(), CLE_SOURCE, sourceId.toString());

    FusionEvenementsSql.fusionner(jdbc, FusionEvenementsSql.ARTISTE, ids);
    fusionnerAbonnementsEtSourcesArtiste(ids, cibleId, sourceId);
    fusionnerMetadonneesArtiste(ids);
    jdbc.update(
        "UPDATE evenement_artistes SET artiste_id = CAST(:cible AS uuid)"
            + " WHERE artiste_id = CAST(:source AS uuid)",
        ids);

    jdbc.update("DELETE FROM artistes WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        NIVEAU_INFO, SOURCE_ADMIN, "fusion artiste cible=" + cibleId + " source=" + sourceId);
    return true;
  }

  private void fusionnerAbonnementsEtSourcesArtiste(
      final Map<String, Object> ids, final UUID cibleId, final UUID sourceId) {
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

    // Dédoublonnage préalable des conflits par type de source (sans lui, deux
    // identifiants du même type, ex. deux ticketmaster, coexistent indéfiniment
    // sur l'artiste fusionné).
    dedupliquerArtisteSourcesAvantFusion(cibleId, sourceId);
    jdbc.update(
        "UPDATE artiste_sources SET artiste_id = CAST(:cible AS uuid) WHERE artiste_id ="
            + " CAST(:source AS uuid)",
        ids);

    // une fusion admin est elle-même une résolution manuelle — efface le signal
    // d'intervention hérité de l'artiste absorbé ou déjà posé sur la cible.
    jdbc.update(
        "UPDATE artiste_sources SET necessite_intervention_le = NULL WHERE artiste_id ="
            + " CAST(:cible AS uuid)",
        ids);
  }

  private void fusionnerMetadonneesArtiste(final Map<String, Object> ids) {
    jdbc.update(
        """
        UPDATE artistes SET
          wikidata_id = COALESCE(wikidata_id, (SELECT wikidata_id FROM artistes WHERE id = CAST(:source AS uuid))),
          deezer_id   = COALESCE(deezer_id, (SELECT deezer_id FROM artistes WHERE id = CAST(:source AS uuid))),
          nom_affiche = COALESCE(nom_affiche, (SELECT nom_affiche FROM artistes WHERE id = CAST(:source AS uuid)))
        WHERE id = CAST(:cible AS uuid)
        """,
        ids);
  }

  /**
   * Supprime, avant réassignation, les doublons d'{@code artiste_sources}
   * qu'une fusion créerait sinon (PK {@code (source, identifiant_externe)}
   * indépendante d'{@code artiste_id}) — garde la ligne la plus saine (non
   * suspecte, puis {@code nb_zero_consecutifs} le plus bas).
   * @param cibleId UUID de l'artiste à conserver
   * @param sourceId UUID de l'artiste à absorber
   */
  private void dedupliquerArtisteSourcesAvantFusion(final UUID cibleId, final UUID sourceId) {
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
            FROM artiste_sources src
            JOIN artiste_sources dst ON dst.source = src.source AND dst.artiste_id = CAST(:cible AS uuid)
            WHERE src.artiste_id = CAST(:source AS uuid)
            """,
            Map.of("cible", cibleId.toString(), CLE_SOURCE, sourceId.toString()));

    for (final Map<String, Object> conflit : conflits) {
      final boolean garderSource = FusionSourcesUtils.laSourceEstPlusSaine(conflit);
      final Map<String, Object> paramsSuppression = new HashMap<>();
      paramsSuppression.put(CLE_SOURCE, conflit.get(CLE_SOURCE));
      paramsSuppression.put(
          CLE_ARTISTE_ID, garderSource ? cibleId.toString() : sourceId.toString());
      paramsSuppression.put(
          "identifiantExterne", conflit.get(garderSource ? "id_cible" : "id_source"));
      jdbc.update(
          "DELETE FROM artiste_sources WHERE artiste_id = CAST(:artisteId AS uuid) AND source ="
              + " :source AND identifiant_externe = :identifiantExterne",
          paramsSuppression);
    }
  }
}
