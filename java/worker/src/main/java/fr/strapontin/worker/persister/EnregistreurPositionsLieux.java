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
 * Enregistre la position publiée de chaque lieu (et le nom d'un lieu
 * Ticketmaster, pour le rattacher à ses libellés) sur son mapping et la reporte
 * sur la salle sans position Wikidata ni de source (un géocodage est remplacé)
 * : sans elle, les garde-fous géographiques restaient inopérants.
 * @since 3.0.2
 * @author Kwaite
 */
final class EnregistreurPositionsLieux {

  // Le libellé d'un lieu Eventim, filtre de ses récupérations, n'est
  // jamais réécrit ici.
  private static final String PREFIXE_TICKETMASTER = "ticketmaster:";

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
      final PositionLieu position = c.position();
      if (c.salleSourceId() != null
          && cachesSalles.containsKey(c.salleSourceId())
          && (position.estGeolocalisee()
              || position.codePostal() != null
              || c.salleSourceId().startsWith(PREFIXE_TICKETMASTER))) {
        parMapping.putIfAbsent(c.salleSourceId(), c);
      }
    }
    if (parMapping.isEmpty()) {
      return;
    }
    final MapSqlParameterSource[] params =
        parMapping.values().stream()
            .map(concert -> parametres(concert, cachesSalles))
            .toArray(MapSqlParameterSource[]::new);
    jdbc.batchUpdate(
        """
        UPDATE salle_sources
        SET latitude = COALESCE(:latitude, latitude),
            longitude = COALESCE(:longitude, longitude),
            code_postal = COALESCE(:codePostal, code_postal),
            libelle_source = CASE WHEN source = 'ticketmaster'
                                  THEN COALESCE(:libelle, libelle_source)
                                  ELSE libelle_source END
        WHERE source = :source AND identifiant_externe = :identifiant
        """,
        params);
    jdbc.batchUpdate(
        """
        UPDATE salles SET code_postal = COALESCE(code_postal, :codePostal)
        WHERE id = CAST(:salleId AS uuid)
        """,
        params);
    // Position de la source, à défaut de Wikidata : remplace un géocodage.
    jdbc.batchUpdate(
        """
        UPDATE salles
        SET latitude = :latitude, longitude = :longitude, origine_position = 'sources'
        WHERE id = CAST(:salleId AS uuid) AND CAST(:latitude AS double precision) IS NOT NULL
          AND (origine_position IS NULL OR origine_position = 'geocodage')
        """,
        params);
  }

  private static MapSqlParameterSource parametres(
      final ValeurConcert concert, final Map<String, UUID> cachesSalles) {
    final String salleSourceId = concert.salleSourceId();
    final int separateur = salleSourceId.indexOf(':');
    return new MapSqlParameterSource()
        .addValue("source", salleSourceId.substring(0, separateur))
        .addValue("identifiant", salleSourceId.substring(separateur + 1))
        .addValue("salleId", cachesSalles.get(salleSourceId).toString())
        .addValue("latitude", concert.position().latitude(), Types.DOUBLE)
        .addValue("longitude", concert.position().longitude(), Types.DOUBLE)
        .addValue("codePostal", concert.position().codePostal(), Types.VARCHAR)
        .addValue("libelle", concert.salle(), Types.VARCHAR);
  }
}
