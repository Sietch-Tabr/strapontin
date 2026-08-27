/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.Instant;
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

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  public ConcertsAdminService(NamedParameterJdbcTemplate jdbc, LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /// Liste les concerts flagués, du plus récemment flagué au plus ancien.
  ///
  /// @return concerts en attente de revue
  public List<ConcertSuppressionSuggeree> lister() {
    return jdbc.query(
        """
        SELECT c.id, c.artiste, c.salle, c.ville, c.date, c.heure, c.absences_consecutives,
               c.suppression_suggeree_le
        FROM concerts c
        WHERE c.suppression_suggeree_le IS NOT NULL
        ORDER BY c.suppression_suggeree_le DESC
        """,
        Map.of(),
        (rs, _) ->
            new ConcertSuppressionSuggeree(
                rs.getObject("id", UUID.class),
                rs.getString("artiste"),
                rs.getString("salle"),
                rs.getString("ville"),
                rs.getObject("date", LocalDate.class),
                rs.getObject("heure", LocalTime.class),
                rs.getInt("absences_consecutives"),
                rs.getObject("suppression_suggeree_le", Instant.class)));
  }

  /// Supprime définitivement un concert flagué.
  ///
  /// @param id UUID du concert
  /// @return {@code true} si un concert flagué correspondant a été supprimé
  public boolean confirmerSuppression(UUID id) {
    int lignes =
        jdbc.update(
            "DELETE FROM concerts WHERE id = CAST(:id AS uuid) AND suppression_suggeree_le IS NOT"
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
            UPDATE concerts
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
