/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Résolution d'une salle par la position publiée de son lieu, signal indépendant du nom.
 *
 * <ul>
 *   <li>Recherche : une salle géolocalisée à moins de {@value #RAYON_MEME_LIEU_METRES} m, dans le
 *       même département, au nom doublon et sans qualificatif de sous-salle divergent. Le nom
 *       reste exigé : deux salles mitoyennes (« Théâtre Montparnasse » / « Théâtre de Poche
 *       Montparnasse ») sont distinctes.
 *   <li>Veto : une salle retenue par son nom est écartée si sa position connue est à plus de
 *       {@value #DISTANCE_INCOMPATIBLE_METRES} m de celle du lieu — homonymes de villes
 *       différentes.
 * </ul>
 *
 * @since 3.0.2
 * @author Kwaite
 */
final class ResolveurSallePosition {

  /* package-private : visibilité élargie pour les tests et la simulation (même package). */
  static final double RAYON_MEME_LIEU_METRES = 300;
  // Les positions publiées s'écartent du lieu réel de quelques kilomètres (centre-ville publié
  // pour une salle de périphérie, mesuré jusqu'à 7 km) : seul un autre lieu urbain est exclu.
  static final double DISTANCE_INCOMPATIBLE_METRES = 20_000;
  // Approximation du rayon en degrés pour le préfiltre SQL (au plus ~0,005° en latitude
  // et longitude en France métropolitaine pour 300 m).
  private static final double DEGRES_PREFILTRE = 0.005;

  private final NamedParameterJdbcTemplate jdbc;

  /** Salle candidate et sa distance au lieu, en mètres. */
  record SalleProche(UUID id, double distance) {}

  ResolveurSallePosition(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param nom nom brut du lieu publié
   * @param dept département du lieu, peut être null
   * @param position position publiée du lieu
   * @return la salle la plus proche désignant le même lieu, ou null
   */
  SalleProche chercher(final String nom, final String dept, final PositionLieu position) {
    if (!position.estGeolocalisee()) {
      return null;
    }
    final String nomNorm = Normalisation.normaliserTexte(nom);
    final List<Map<String, Object>> candidats =
        jdbc.queryForList(
            """
            SELECT id, nom, nom_norm, latitude, longitude FROM salles
            WHERE latitude BETWEEN :lat - :delta AND :lat + :delta
              AND longitude BETWEEN :lon - :delta AND :lon + :delta
              AND (CAST(:dept AS text) IS NULL OR dept IS NULL OR dept = :dept)
            """,
            new MapSqlParameterSource()
                .addValue("lat", position.latitude())
                .addValue("lon", position.longitude())
                .addValue("delta", DEGRES_PREFILTRE)
                .addValue("dept", dept, Types.VARCHAR));
    SalleProche meilleure = null;
    for (final Map<String, Object> c : candidats) {
      final double distance =
          DistanceGeo.metres(
              position.latitude(),
              position.longitude(),
              (Double) c.get("latitude"),
              (Double) c.get("longitude"));
      if (distance > RAYON_MEME_LIEU_METRES
          || !CorrespondanceSalle.estDoublonSalle(nomNorm, (String) c.get("nom_norm"))
          || CorrespondanceSalle.qualificatifsDivergents(nom, (String) c.get("nom"))) {
        continue;
      }
      if (meilleure == null || distance < meilleure.distance()) {
        meilleure = new SalleProche((UUID) c.get("id"), distance);
      }
    }
    return meilleure;
  }

  /**
   * @param salleId salle candidate, peut être null
   * @param position position publiée du lieu
   * @return true si la salle et le lieu sont tous deux géolocalisés et trop éloignés pour être
   *     le même lieu
   */
  boolean incompatible(final UUID salleId, final PositionLieu position) {
    if (salleId == null || !position.estGeolocalisee()) {
      return false;
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT latitude, longitude FROM salles WHERE id = CAST(:id AS uuid)"
                + " AND latitude IS NOT NULL AND longitude IS NOT NULL",
            Map.of("id", salleId.toString()));
    if (lignes.isEmpty()) {
      return false;
    }
    return DistanceGeo.metres(
            position.latitude(),
            position.longitude(),
            (Double) lignes.get(0).get("latitude"),
            (Double) lignes.get(0).get("longitude"))
        > DISTANCE_INCOMPATIBLE_METRES;
  }
}
