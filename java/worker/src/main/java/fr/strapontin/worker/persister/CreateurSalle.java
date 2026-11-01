/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Création d'une salle pour un concert qu'aucune résolution n'a rattaché —
 * extrait de {@link ResolveurSalleParConcert} (taille).
 * @since 3.0.1
 * @author Kwaite
 */
final class CreateurSalle {

  private static final String CLE_SALLE_CLE = "salleCle";
  private static final String CLE_NOM = "nom";
  private static final String CLE_DEPT = "dept";

  private final NamedParameterJdbcTemplate jdbc;
  private final Function<Set<UUID>, Map<UUID, String>> deptsConnus;

  /* package-private : instancié par ResolveurSalleParConcert (même package). */
  CreateurSalle(
      final NamedParameterJdbcTemplate jdbc,
      final Function<Set<UUID>, Map<UUID, String>> deptsConnus) {
    this.jdbc = jdbc;
    this.deptsConnus = deptsConnus;
  }

  /* package-private : appelé par ResolveurSalleParConcert (même package). */
  UUID creer(
      final ValeurConcert concert, final Optional<ClientNominatim.Coordonnees> coordsGeocodees) {
    final String nomNorm = Normalisation.normaliserTexte(concert.salle());
    final UUID salleId = insererSalle(nomNorm, nomNorm, concert, coordsGeocodees);
    // salle_cle est unique : un homonyme déjà établi dans un autre département
    // (nom générique) serait sinon renvoyé à la place de la salle à créer.
    final String deptExistant = deptsConnus.apply(Set.of(salleId)).get(salleId);
    if (concert.dept() == null || deptExistant == null || deptExistant.equals(concert.dept())) {
      return salleId;
    }
    return insererSalle(nomNorm + " " + concert.dept(), nomNorm, concert, coordsGeocodees);
  }

  private UUID insererSalle(
      final String salleCle,
      final String nomNorm,
      final ValeurConcert concert,
      final Optional<ClientNominatim.Coordonnees> coordsGeocodees) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue(CLE_SALLE_CLE, salleCle)
            .addValue(CLE_NOM, concert.salle())
            .addValue("nomNorm", nomNorm)
            .addValue(CLE_DEPT, concert.dept())
            .addValue("ville", concert.ville())
            .addValue(
                "latitude", coordsGeocodees.map(ClientNominatim.Coordonnees::latitude).orElse(null))
            .addValue(
                "longitude",
                coordsGeocodees.map(ClientNominatim.Coordonnees::longitude).orElse(null));
    jdbc.update(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept, ville, latitude, longitude,"
            + " origine_position) VALUES (:salleCle, :nom, :nomNorm, :dept, :ville, :latitude,"
            + " :longitude, CASE WHEN CAST(:latitude AS double precision) IS NOT NULL"
            + " THEN 'geocodage' END)"
            + " ON CONFLICT (salle_cle) DO NOTHING",
        params);
    return jdbc.queryForObject(
        "SELECT id FROM salles WHERE salle_cle = :salleCle",
        Map.of(CLE_SALLE_CLE, salleCle),
        UUID.class);
  }
}
