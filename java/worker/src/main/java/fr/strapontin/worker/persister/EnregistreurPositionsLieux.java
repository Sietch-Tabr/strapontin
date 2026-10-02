/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.PositionLieu;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Enregistre la position publiée de chaque lieu source (coordonnées, code postal) sur son mapping
 * {@code salle_sources}, et la reporte sur la salle tant qu'elle n'en a pas : sans position, les
 * garde-fous géographiques (fusion, résolution, dispersion) restaient inopérants pour la plupart
 * des salles.
 *
 * @since 3.0.2
 * @author Kwaite
 */
final class EnregistreurPositionsLieux {

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par PersisterConcerts (même package). */
  EnregistreurPositionsLieux(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param concerts concerts dont l'identifiant source de salle est résolu
   * @param cachesSalles identifiant source préfixé → salle
   */
  /* package-private : appelé par PersisterConcerts (même package). */
  void enregistrer(final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Map<String, ValeurConcert> parMapping = new LinkedHashMap<>();
    for (final ValeurConcert c : concerts) {
      final PositionLieu p = c.position();
      if (c.salleSourceId() != null
          && cachesSalles.containsKey(c.salleSourceId())
          && (p.estGeolocalisee() || p.codePostal() != null)) {
        parMapping.putIfAbsent(c.salleSourceId(), c);
      }
    }
    if (parMapping.isEmpty()) {
      return;
    }
    final MapSqlParameterSource[] params =
        parMapping.values().stream()
            .map(
                c -> {
                  final int separateur = c.salleSourceId().indexOf(':');
                  return new MapSqlParameterSource()
                      .addValue("source", c.salleSourceId().substring(0, separateur))
                      .addValue("identifiant", c.salleSourceId().substring(separateur + 1))
                      .addValue("salleId", cachesSalles.get(c.salleSourceId()).toString())
                      .addValue("latitude", c.position().latitude(), Types.DOUBLE)
                      .addValue("longitude", c.position().longitude(), Types.DOUBLE)
                      .addValue("codePostal", c.position().codePostal(), Types.VARCHAR);
                })
            .toArray(MapSqlParameterSource[]::new);
    jdbc.batchUpdate(
        """
        UPDATE salle_sources
        SET latitude = COALESCE(:latitude, latitude),
            longitude = COALESCE(:longitude, longitude),
            code_postal = COALESCE(:codePostal, code_postal)
        WHERE source = :source AND identifiant_externe = :identifiant
        """,
        params);
    jdbc.batchUpdate(
        """
        UPDATE salles
        SET latitude = COALESCE(latitude, :latitude),
            longitude = CASE WHEN latitude IS NULL THEN :longitude ELSE longitude END,
            code_postal = COALESCE(code_postal, :codePostal)
        WHERE id = CAST(:salleId AS uuid)
        """,
        params);
  }
}
