/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Liste paginée des salles pour l'onglet admin « Sources » (candidates Digitick / déjà
 * configurées).
 *
 * <p>Extrait d'{@link SourcesService} pour rester sous la limite de 500 lignes par fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
@Service
class SourcesSallesListingService {

  private static final Logger LOG = LoggerFactory.getLogger(SourcesSallesListingService.class);
  private static final int TAILLE_PAGE_SALLES = 50;
  private static final String CLE_NOM = "nom";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * @param jdbc accès JDBC
   */
  /*
   * package-private : instancié uniquement par SourcesService (même package).
   */
  SourcesSallesListingService(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Liste paginée des salles pour l'onglet admin « Sources ».
   *
   * @param digitick {@code true} pour les salles déjà configurées avec un slug Digitick, {@code
   *     false} pour celles sans identifiant Ticketmaster ni Eventim (candidates à une configuration
   *     manuelle)
   * @param page numéro de page (0-indexé)
   * @param nom filtre nom partiel (insensible à la casse), peut être null
   * @return page de résultats
   */
  /* package-private : appelé par SourcesService (même package). */
  ReponseSallesSources lister(final boolean digitick, final int page, final String nom) {
    return digitick ? listerSallesAvecDigitick(page, nom) : listerSallesSansTmNiEventim(page, nom);
  }

  private ReponseSallesSources listerSallesAvecDigitick(final int page, final String nom) {
    final int pageNum = Math.max(0, page);
    final StringBuilder where = new StringBuilder(50);
    final Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND s.nom ILIKE :nom ");
      params.put(CLE_NOM, "%" + nom.strip() + "%");
    }
    params.put("taille", TAILLE_PAGE_SALLES);
    params.put("offset", pageNum * TAILLE_PAGE_SALLES);
    final long[] total = {0};
    final List<SalleSourceAdmin> salles =
        jdbc.query(
            "SELECT s.id, s.nom, s.ville, s.dept, ss.identifiant_externe AS digitick_slug,"
                + " COUNT(*) OVER() AS total_filtre"
                + " FROM salles s"
                + " JOIN salle_sources ss ON ss.salle_id = s.id AND ss.source = 'digitick'"
                + " WHERE 1=1 "
                + where
                + " ORDER BY lower(s.nom) LIMIT :taille OFFSET :offset",
            params,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new SalleSourceAdmin(
                  rs.getObject("id", UUID.class),
                  rs.getString(CLE_NOM),
                  rs.getString("digitick_slug"),
                  rs.getString("ville"),
                  rs.getString("dept"));
            });

    if (salles.isEmpty() && pageNum > 0) {
      total[0] =
          compterAvecFallback(
              "SELECT COUNT(*) FROM salles s JOIN salle_sources ss ON ss.salle_id = s.id AND"
                  + " ss.source = 'digitick' WHERE 1=1 "
                  + where,
              params,
              "salles avec Digitick");
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total[0] / TAILLE_PAGE_SALLES));
    return new ReponseSallesSources(salles, pageNum, totalPages, total[0]);
  }

  private ReponseSallesSources listerSallesSansTmNiEventim(final int page, final String nom) {
    final int pageNum = Math.max(0, page);
    final StringBuilder where = new StringBuilder(50);
    final Map<String, Object> params = new HashMap<>();
    if (nom != null && !nom.isBlank()) {
      where.append(" AND s.nom ILIKE :nom ");
      params.put(CLE_NOM, "%" + nom.strip() + "%");
    }
    params.put("taille", TAILLE_PAGE_SALLES);
    params.put("offset", pageNum * TAILLE_PAGE_SALLES);

    final PageSallesSansTmNiEventim resultatPage = chargerSallesSansTmNiEventim(where, params);

    long total = resultatPage.total();
    // COUNT(*) OVER() porte ici sur les lignes post-HAVING (fenêtre appliquée
    // après le filtrage par groupe), donc valide même avec ce HAVING — seul le
    // repli page-vide doit le reproduire.
    if (resultatPage.salles().isEmpty() && pageNum > 0) {
      total = compterSallesSansTmNiEventim(where, params);
    }

    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_SALLES));
    return new ReponseSallesSources(resultatPage.salles(), pageNum, totalPages, total);
  }

  private record PageSallesSansTmNiEventim(List<SalleSourceAdmin> salles, long total) {}

  private PageSallesSansTmNiEventim chargerSallesSansTmNiEventim(
      final StringBuilder where, final Map<String, Object> params) {
    final long[] total = {0};
    final List<SalleSourceAdmin> salles =
        jdbc.query(
            "SELECT s.id, s.nom, s.ville, s.dept,"
                + " MAX(CASE WHEN ss.source = 'digitick' THEN ss.identifiant_externe END) AS"
                + " digitick_slug, COUNT(*) OVER() AS total_filtre"
                + " FROM salles s"
                + " LEFT JOIN salle_sources ss ON ss.salle_id = s.id"
                + " WHERE 1=1 "
                + where
                + " GROUP BY s.id, s.nom, s.ville, s.dept"
                + " HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe"
                + " END) IS NULL AND MAX(CASE WHEN ss.source = 'eventim' THEN"
                + " ss.identifiant_externe END) IS NULL"
                + " ORDER BY lower(s.nom) LIMIT :taille OFFSET :offset",
            params,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new SalleSourceAdmin(
                  rs.getObject("id", UUID.class),
                  rs.getString(CLE_NOM),
                  rs.getString("digitick_slug"),
                  rs.getString("ville"),
                  rs.getString("dept"));
            });
    return new PageSallesSansTmNiEventim(salles, total[0]);
  }

  private long compterSallesSansTmNiEventim(
      final StringBuilder where, final Map<String, Object> params) {
    final Long totalVide =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT s.id"
                + " FROM salles s"
                + " LEFT JOIN salle_sources ss ON ss.salle_id = s.id"
                + " WHERE 1=1 "
                + where
                + " GROUP BY s.id"
                + " HAVING MAX(CASE WHEN ss.source = 'ticketmaster' THEN ss.identifiant_externe"
                + " END) IS NULL AND MAX(CASE WHEN ss.source = 'eventim' THEN"
                + " ss.identifiant_externe END) IS NULL) sous_requete",
            params,
            Long.class);
    return totalVide != null ? totalVide : 0;
  }

  private Integer compterAvecFallback(
      final String sql, final Map<String, Object> params, final String descriptionEchec) {
    try {
      final Integer nb = jdbc.queryForObject(sql, params, Integer.class);
      return nb != null ? nb : 0;
    } catch (Exception e) {
      LOG.debug("Impossible de compter {} : {}", descriptionEchec, e.getMessage());
      return 0;
    }
  }
}
