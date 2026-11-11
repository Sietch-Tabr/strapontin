/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import fr.strapontin.core.FusionEvenementsSql;
import fr.strapontin.core.FusionSallesSql;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion du référentiel salles pour l'onglet admin : liste paginée,
 * édition du nom affiché, fusion de doublons — porte en service runtime
 * le gabarit des fusions ad hoc passées (réassignation concerts, puis
 * abonnements, puis sources externes, avant suppression de la source).
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
               string_agg(DISTINCT ss.libelle_source, ' | ')
                 FILTER (WHERE ss.source = 'eventim' AND ss.libelle_source IS NOT NULL)
                 AS lieu_eventim,
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

  private static final String SOURCE_ADMIN = "admin";

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
   * Filtres de la liste (nom, ville, département, statut a_jour, a_verifier ou
   * invalide : null = sans filtre) ; {@code ids} restreint à des salles précises
   * (comparaison), {@code sansSource} ne garde que les salles sans aucune source.
   * @param page numéro de page (0-indexé)
   * @param wikidataManquant filtre les salles sans wikidata_id
   */
  public record FiltresRechercheSalles(
      int page,
      String nom,
      String ville,
      String dept,
      String statut,
      boolean wikidataManquant,
      List<UUID> ids,
      boolean sansSource) {

    /** Sans filtre sur les sources. */
    public FiltresRechercheSalles(
        final int page,
        final String nom,
        final String ville,
        final String dept,
        final String statut,
        final boolean wikidataManquant,
        final List<UUID> ids) {
      this(page, nom, ville, dept, statut, wikidataManquant, ids, false);
    }

    /** Sans restriction à des salles précises. */
    public FiltresRechercheSalles(
        final int page,
        final String nom,
        final String ville,
        final String dept,
        final String statut,
        final boolean wikidataManquant) {
      this(page, nom, ville, dept, statut, wikidataManquant, List.of(), false);
    }
  }

  /**
   * Liste paginée du référentiel salles, filtrée par nom/ville/dept/statut.
   *
   * @param filtresRecherche filtres de recherche (voir
   *     {@link FiltresRechercheSalles} )
   * @return page de résultats
   */
  public ReponseReferentielSalles lister(final FiltresRechercheSalles filtresRecherche) {
    final int pageNum = Math.max(0, filtresRecherche.page());
    final FiltresSalles filtres = construireFiltresSalles(filtresRecherche);

    final Map<String, Object> paramsPage = new HashMap<>(filtres.params());
    paramsPage.put("taille", TAILLE_PAGE_DEFAUT);
    paramsPage.put("offset", pageNum * TAILLE_PAGE_DEFAUT);
    final PageSalles resultatPage = chargerPageSalles(filtres.where(), paramsPage);

    long total = resultatPage.total();
    // page vide (offset au-delà du total) : total_filtre n'est renvoyé par
    // aucune ligne, un second aller-retour reste alors nécessaire pour
    // connaître le total réel.
    if (resultatPage.salles().isEmpty() && pageNum > 0) {
      total = compterSallesFiltrees(filtres.where(), filtres.params());
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseReferentielSalles(resultatPage.salles(), pageNum, totalPages, total);
  }

  private record FiltresSalles(StringBuilder where, Map<String, Object> params) {}

  private FiltresSalles construireFiltresSalles(final FiltresRechercheSalles filtresRecherche) {
    final StringBuilder where = new StringBuilder(120).append(" WHERE 1=1 ");
    final Map<String, Object> params = new HashMap<>();
    ajouterFiltreLike(where, params, "nom", filtresRecherche.nom());
    ajouterFiltreLike(where, params, "ville", filtresRecherche.ville());
    ajouterFiltreExact(where, params, "dept", filtresRecherche.dept());
    ajouterFiltreExact(where, params, "statut", filtresRecherche.statut());
    if (filtresRecherche.wikidataManquant()) {
      where.append(" AND wikidata_id IS NULL ");
    }
    if (filtresRecherche.sansSource()) {
      where.append(" AND sources = '' ");
    }
    if (filtresRecherche.ids() != null && !filtresRecherche.ids().isEmpty()) {
      where.append(" AND id IN (:ids) ");
      params.put("ids", filtresRecherche.ids());
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
                + " statut, nom_parent, lieu_eventim, COUNT(*) OVER() AS total_filtre"
                + " FROM salles_agr"
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
                  rs.getString("nom_parent"),
                  List.of(),
                  rs.getString("lieu_eventim"));
            });
    return new PageSalles(SuspicionsSalles.ajouter(jdbc, salles), total[0]);
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
   * Efface l'enrichissement auto d'une salle (élément Wikidata, nom
   * d'affichage, ville et position venue de l'élément) pour la remettre
   * candidate à une nouvelle découverte (homonyme jamais auto-corrigé).
   * @param salleId UUID de la salle
   * @return {@code true} si la salle existe et a été réinitialisée
   */
  public boolean reinitialiserEnrichissement(final UUID salleId) {
    final int nb =
        jdbc.update(
            """
            UPDATE salles SET wikidata_id = NULL, nom_affiche = NULL, ville = NULL,
              latitude = CASE WHEN origine_position = 'wikidata' THEN NULL ELSE latitude END,
              longitude = CASE WHEN origine_position = 'wikidata' THEN NULL ELSE longitude END,
              origine_position = CASE WHEN origine_position = 'wikidata' THEN NULL
                                      ELSE origine_position END
            WHERE id = CAST(:id AS uuid)
            """,
            Map.of("id", salleId.toString()));
    if (nb > 0) {
      logAdminService.logAdmin(
          "info",
          SOURCE_ADMIN,
          "enrichissement salle réinitialisé",
          Map.of("salleId", salleId.toString()));
    }
    return nb > 0;
  }

  /**
   * Modifie le nom affiché d'une salle (override manuel prioritaire sur
   * l'enrichissement auto).
   * @param salleId UUID de la salle
   * @param nomAffiche nouveau nom affiché, ou null pour revenir à
   *     l'enrichissement automatique
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
   * Fusionne deux salles : la salle source est absorbée par la salle
   * cible (concerts, abonnements, sources externes), puis supprimée.
   * @param cibleId UUID de la salle à conserver
   * @param sourceId UUID de la salle à absorber
   * @return {@code true} si fusionnée, {@code false} si UUID inconnu
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

    FusionEvenementsSql.fusionner(jdbc, FusionEvenementsSql.SALLE, ids);
    reporterAbonnementsEtLieux(ids, cibleId);
    FusionSallesSql.reporterMetadonnees(jdbc, ids);
    detacherSousSallesEnCycle(
        FusionSallesSql.reassignerSousSallesSansCycle(jdbc, cibleId, sourceId), cibleId);

    // 7. Suppression de la salle source
    jdbc.update("DELETE FROM salles WHERE id = CAST(:source AS uuid)", ids);

    logAdminService.logAdmin(
        "info", SOURCE_ADMIN, "fusion salle cible=" + cibleId + " source=" + sourceId);
    return true;
  }

  private void reporterAbonnementsEtLieux(final Map<String, Object> ids, final UUID cibleId) {
    final List<String> lieuxReportes = FusionSallesSql.reporterAbonnementsEtLieux(jdbc, ids);
    if (!lieuxReportes.isEmpty()) {
      logAdminService.logAdmin(
          "info",
          SOURCE_ADMIN,
          "lieux reportés sur la salle conservée après fusion",
          Map.of("salleCible", cibleId.toString(), "lieux", lieuxReportes));
    }
  }

  private void detacherSousSallesEnCycle(final List<UUID> detachees, final UUID cibleId) {
    for (final UUID enfant : detachees) {
      logAdminService.logAdmin(
          "warn",
          SOURCE_ADMIN,
          "cycle salle_parent_id évité lors d'une fusion — sous-salle détachée enfant="
              + enfant
              + " cible="
              + cibleId);
    }
  }
}
