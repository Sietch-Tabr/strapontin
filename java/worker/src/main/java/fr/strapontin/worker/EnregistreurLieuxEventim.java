/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import fr.strapontin.sources.eventim.EventimSource;
import fr.strapontin.sources.eventim.LieuEventim;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relevé des lieux Eventim observés lors des fetchs (libellé, ville,
 * coordonnées) dans {@code salle_sources}.
 * @since 3.0.1
 * @author Kwaite
 */
@Component
public class EnregistreurLieuxEventim {

  private final NamedParameterJdbcTemplate jdbc;
  private final EventimSource eventim;

  /**
   * @param jdbc accès JDBC
   * @param eventim source des lieux observés
   */
  public EnregistreurLieuxEventim(
      final NamedParameterJdbcTemplate jdbc, final EventimSource eventim) {
    this.jdbc = jdbc;
    this.eventim = eventim;
  }

  /**
   * Renseigne dans {@code salle_sources} le libellé, la ville et les
   * coordonnées des lieux Eventim observés depuis le dernier relevé — requis
   * pour interroger Eventim par lieu ou par ville. À appeler après
   * persistance : un mapping créé par ce même cycle existe alors déjà.
   */
  /* package-private : appelé par les exécuteurs de fetch (même package). */
  void enregistrer() {
    final List<LieuEventim> lieux = eventim.preleverLieuxObserves();
    if (lieux.isEmpty()) {
      return;
    }
    final MapSqlParameterSource[] params =
        lieux.stream()
            .map(
                lieu ->
                    new MapSqlParameterSource()
                        .addValue("identifiant", lieu.identifiant())
                        .addValue("libelle", lieu.libelle())
                        .addValue("ville", lieu.ville(), Types.VARCHAR)
                        .addValue("latitude", lieu.latitude(), Types.DOUBLE)
                        .addValue("longitude", lieu.longitude(), Types.DOUBLE))
            .toArray(MapSqlParameterSource[]::new);
    jdbc.batchUpdate(
        "UPDATE salle_sources SET libelle_source = :libelle,"
            + " ville_source = COALESCE(:ville, ville_source),"
            + " latitude = COALESCE(:latitude, latitude),"
            + " longitude = COALESCE(:longitude, longitude)"
            + " WHERE source = 'eventim' AND identifiant_externe = :identifiant",
        params);
  }
}
