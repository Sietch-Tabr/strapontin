/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Revue manuelle des concerts flagués par la purge nocturne (`suppression_suggeree_le`) pour
/// l'onglet admin Concerts — la suppression définitive reste une action manuelle plutôt
/// qu'automatique, un concert réel peut être flagué à tort (doublon de salle jamais fusionné entre
/// sources, concert catalogué sous une autre attraction que l'artiste suivi).
///
/// @since 2.8.3
/// @author Kwaite
@Service
public class ConcertsAdminService {

  private static final int TAILLE_PAGE_DEFAUT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public ConcertsAdminService(NamedParameterJdbcTemplate jdbc, LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Liste paginée des concerts flagués, du plus récemment flagué au plus ancien.
  ///
  /// @param page numéro de page (0-indexé)
  /// @return page de concerts en attente de revue
  public ReponseConcertsAConfirmer lister(int page) {
    int pageNum = Math.max(0, page);
    Map<String, Object> paramsPage =
        Map.of("taille", TAILLE_PAGE_DEFAUT, "offset", pageNum * TAILLE_PAGE_DEFAUT);
    long[] total = {0};
    List<ConcertSuppressionSuggeree> concerts =
        jdbc.query(
            """
            SELECT c.id, c.artiste, c.salle, c.ville, c.date, c.heure, c.absences_consecutives,
                   c.suppression_suggeree_le, COUNT(*) OVER() AS total_filtre
            FROM concerts c
            WHERE c.suppression_suggeree_le IS NOT NULL
            ORDER BY c.suppression_suggeree_le DESC
            LIMIT :taille OFFSET :offset
            """,
            paramsPage,
            (rs, _) -> {
              total[0] = rs.getLong("total_filtre");
              return new ConcertSuppressionSuggeree(
                  rs.getObject("id", UUID.class),
                  rs.getString("artiste"),
                  rs.getString("salle"),
                  rs.getString("ville"),
                  rs.getObject("date", LocalDate.class),
                  rs.getObject("heure", LocalTime.class),
                  rs.getInt("absences_consecutives"),
                  rs.getTimestamp("suppression_suggeree_le").toInstant());
            });

    // page vide (offset au-delà du total) : total_filtre n'est renvoyé par aucune ligne, un
    // second aller-retour reste alors nécessaire pour connaître le total réel.
    if (concerts.isEmpty() && pageNum > 0) {
      Long totalVide =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM concerts WHERE suppression_suggeree_le IS NOT NULL",
              Map.of(),
              Long.class);
      total[0] = totalVide != null ? totalVide : 0;
    }

    int totalPages = Math.max(1, (int) Math.ceil((double) total[0] / TAILLE_PAGE_DEFAUT));
    return new ReponseConcertsAConfirmer(concerts, pageNum, totalPages, total[0]);
  }

  /// Supprime définitivement un concert flagué.
  ///
  /// @param id UUID du concert
  /// @return {@code true} si un concert flagué correspondant a été supprimé
  public boolean confirmerSuppression(UUID id) {
    int lignes =
        jdbc.update(
            "DELETE FROM evenements WHERE id = CAST(:id AS uuid) AND suppression_suggeree_le IS NOT"
                + " NULL",
            Map.of("id", id.toString()));
    if (lignes > 0) {
      logAdminService.logAdmin(
          "info",
          "admin",
          "concert flagué supprimé définitivement",
          Map.of("concertId", id.toString()));
    }
    return lignes > 0;
  }

  /// Annule le flag de suppression d'un concert (faux positif) — remet aussi le compteur d'absences
  /// à zéro pour éviter un re-flag immédiat au cycle de purge suivant.
  ///
  /// @param id UUID du concert
  /// @return {@code true} si un concert flagué correspondant a été restauré
  public boolean annulerSuppression(UUID id) {
    int lignes =
        jdbc.update(
            """
            UPDATE evenements
            SET suppression_suggeree_le = NULL, absences_consecutives = 0, absence_detectee_le = NULL
            WHERE id = CAST(:id AS uuid) AND suppression_suggeree_le IS NOT NULL
            """,
            Map.of("id", id.toString()));
    if (lignes > 0) {
      logAdminService.logAdmin(
          "info", "admin", "flag de suppression annulé", Map.of("concertId", id.toString()));
    }
    return lignes > 0;
  }
}
