/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.core.DistanceGeo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Une collision {@code wikidata_id} n'est un doublon ou une erreur possible
 * qu'entre deux salles d'un même secteur : l'élément d'un lieu d'une autre
 * ville est seulement écarté, sans signalement.
 * @since 3.0.3
 * @author Kwaite
 */
final class CollisionWikidataSalle {

  private static final double DISTANCE_MAX_METRES = 20_000;
  private static final String LATITUDE = "latitude";
  private static final String LONGITUDE = "longitude";

  private CollisionWikidataSalle() {}

  /**
   * @param jdbc accès JDBC
   * @param salleId salle à laquelle l'élément était proposé
   * @param porteuse salle qui porte déjà l'élément ({@code dept}, position)
   * @return {@code false} si les départements diffèrent ou si les positions
   *     sont à plus de 20 km ; {@code true} sinon, faute de preuve du contraire
   */
  /* package-private : appelé par EnrichissementSallesService (même package). */
  static boolean memeLieuPossible(
      final NamedParameterJdbcTemplate jdbc,
      final UUID salleId,
      final Map<String, Object> porteuse) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT dept, latitude, longitude FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()));
    if (lignes.isEmpty()) {
      return true;
    }
    final Map<String, Object> salle = lignes.get(0);
    final Object dept = salle.get("dept");
    if (dept != null && porteuse.get("dept") != null && !dept.equals(porteuse.get("dept"))) {
      return false;
    }
    return !(salle.get(LATITUDE) instanceof Double lat
        && salle.get(LONGITUDE) instanceof Double lon
        && porteuse.get(LATITUDE) instanceof Double latP
        && porteuse.get(LONGITUDE) instanceof Double lonP
        && DistanceGeo.metres(lat, lon, latP, lonP) > DISTANCE_MAX_METRES);
  }
}
