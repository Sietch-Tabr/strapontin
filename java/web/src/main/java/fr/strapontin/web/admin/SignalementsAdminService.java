/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Vue unifiée des signalements de matching : agrège en lecture {@code
 * signalements_matching} et les mécanismes établis ({@code
 * necessite_intervention_le}/{@code contamination_signalee}/{@code
 * doublon_signale}), résolvables depuis Salles/Artistes.
 * @since 2.9.0
 * @author Kwaite
 */
@Service
public class SignalementsAdminService {

  private static final int TAILLE_PAGE_DEFAUT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * @param jdbc accès JDBC
   * @param logAdminService lecture des mécanismes de signalement non migrés
   */
  public SignalementsAdminService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Liste paginée des signalements actifs, du plus récent au plus ancien
   * (entrées sans horodatage dédié en dernier) — total via une requête
   * séparée, plus lisible que {@code COUNT(*) OVER()} sur 4 sous-requêtes.
   * @param page numéro de page (0-indexé)
   * @return page de signalements actifs
   */
  public ReponseSignalements lister(final int page) {
    final int pageNum = Math.max(0, page);
    final Map<String, Object> paramsPage =
        Map.of("taille", TAILLE_PAGE_DEFAUT, "offset", pageNum * TAILLE_PAGE_DEFAUT);
    final List<EntreeSignalement> signalements =
        jdbc.query(
            """
            SELECT * FROM (
              SELECT 'nouveau' AS categorie, sm.id::text AS id, sm.type AS type,
                     sm.entite_type AS entite_type, sm.entite_id::text AS entite_id,
                     sm.confiance AS confiance, sm.detecte_le AS detecte_le,
                     sm.details::text AS details
              FROM signalements_matching sm WHERE sm.resolu_le IS NULL
              UNION ALL
              SELECT 'necessite_intervention', a.artiste_id::text, 'necessite_intervention',
                     'artiste', a.artiste_id::text, 'moyenne', a.necessite_intervention_le, NULL
              FROM artiste_sources a WHERE a.necessite_intervention_le IS NOT NULL
              UNION ALL
              SELECT 'contamination_salle', s.id::text, 'contamination_salle', 'salle',
                     s.id::text, 'moyenne', NULL, s.contamination_signalee
              FROM salles s WHERE s.contamination_signalee IS NOT NULL
              UNION ALL
              SELECT 'doublon_salle', s.id::text, 'doublon_salle', 'salle', s.id::text, 'moyenne',
                     NULL, s.doublon_signale
              FROM salles s WHERE s.doublon_signale IS NOT NULL
            ) t
            ORDER BY t.detecte_le DESC NULLS LAST
            LIMIT :taille OFFSET :offset
            """,
            paramsPage,
            (rs, _) -> {
              final Object detecteLeBrut = rs.getObject("detecte_le");
              return new EntreeSignalement(
                  rs.getString("categorie"),
                  rs.getString("id"),
                  rs.getString("type"),
                  rs.getString("entite_type"),
                  rs.getString("entite_id"),
                  rs.getString("confiance"),
                  detecteLeBrut != null ? ((java.sql.Timestamp) detecteLeBrut).toInstant() : null,
                  rs.getString("details"));
            });

    final long total = totalActifs();
    final int totalPages = Math.max(1, (int) Math.ceil((double) total / TAILLE_PAGE_DEFAUT));
    return new ReponseSignalements(signalements, pageNum, totalPages, total);
  }

  private long totalActifs() {
    final Long total =
        jdbc.queryForObject(
            """
            SELECT
              (SELECT COUNT(*) FROM signalements_matching WHERE resolu_le IS NULL)
              + (SELECT COUNT(*) FROM artiste_sources WHERE necessite_intervention_le IS NOT NULL)
              + (SELECT COUNT(*) FROM salles WHERE contamination_signalee IS NOT NULL)
              + (SELECT COUNT(*) FROM salles WHERE doublon_signale IS NOT NULL)
            """,
            Map.of(),
            Long.class);
    return total != null ? total : 0;
  }

  /**
   * Marque un signalement de la table {@code signalements_matching} comme
   * résolu manuellement.
   *
   * @param id identifiant numérique du signalement
   * @return {@code true} si un signalement actif correspondant a été résolu
   */
  public boolean resoudre(final long id) {
    final int lignes =
        jdbc.update(
            "UPDATE signalements_matching SET resolu_le = now()"
                + " WHERE id = :id AND resolu_le IS NULL",
            Map.of("id", id));
    if (lignes > 0) {
      logAdminService.logAdmin(
          "info", "admin", "signalement résolu manuellement", Map.of("signalementId", id));
    }
    return lignes > 0;
  }
}
