/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.enrichissement.ClientNominatim;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Résolution d'une salle par proximité GPS (dernier recours du géocodage
 * Nominatim) — extrait de {@link ResolveurSalleParConcert} (dette de
 * taille pure).
 * @since 3.0.0
 * @author Kwaite
 */
final class ResolveurSalleParProximiteGps {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurSalleParProximiteGps.class);
  private static final String CLE_DEPT = "dept";
  private static final double JACCARD_AUCUN_CHEVAUCHEMENT = 0.0;

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par ResolveurSalleParConcert (même
   * package). */
  ResolveurSalleParProximiteGps(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Dernier recours : salle proche des coordonnées, si les noms n'ont
   * aucun token commun (un chevauchement même faible = ambiguïté).
   * @param coords coordonnées géocodées du nom entrant
   * @param nom nom brut, pour le garde-fou de nom
   * @param dept département connu (filtre, désactivé si absent)
   * @param source billetterie du concert : une salle qu'elle nomme déjà autrement n'est
   *     pas candidate
   * @return UUID de la salle la plus proche dans le rayon autorisé, ou null
   */
  /* package-private : appelé aussi par les tests du même package. */
  UUID chercher(
      final ClientNominatim.Coordonnees coords,
      final String nom,
      final String dept,
      final String source) {
    final String nomNorm = Normalisation.normaliserTexte(nom);
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT s.id, s.nom_norm, s.latitude, s.longitude FROM salles s"
                + " WHERE s.latitude IS NOT NULL AND s.longitude IS NOT NULL"
                + " AND (:dept IS NULL OR s.dept IS NULL OR s.dept = :dept)"
                // Deux noms sans mot commun pour une même billetterie : deux lieux voisins.
                + " AND NOT EXISTS (SELECT 1 FROM salle_sources ss WHERE ss.salle_id = s.id"
                + " AND ss.source = :source"
                + " AND ss.libelle_source IS NOT NULL)",
            new MapSqlParameterSource()
                .addValue(CLE_DEPT, dept, Types.VARCHAR)
                .addValue("source", source, Types.VARCHAR));
    // La plus proche, pas la première dans le rayon : en centre-ville, plusieurs
    // salles distinctes tiennent dans le même rayon.
    UUID plusProche = null;
    double distanceMin = Double.MAX_VALUE;
    for (final Map<String, Object> ligne : lignes) {
      final Double latitude = (Double) ligne.get("latitude");
      final Double longitude = (Double) ligne.get("longitude");
      final String nomNormCandidat = (String) ligne.get("nom_norm");
      if (CorrespondanceSalle.jaccardTokens(nomNorm, nomNormCandidat) <= JACCARD_AUCUN_CHEVAUCHEMENT
          && FusionSallesClePorteeService.coordonneesConnuesEtProches(
              coords.latitude(), coords.longitude(), latitude, longitude)) {
        final double distance =
            DistanceGeo.metres(coords.latitude(), coords.longitude(), latitude, longitude);
        if (distance < distanceMin) {
          distanceMin = distance;
          plusProche = (UUID) ligne.get("id");
        }
      }
    }
    if (plusProche != null) {
      LOG.info(
          "salle résolue par proximité GPS (géocodage dernier recours) uuid={} distance={}m",
          plusProche,
          Math.round(distanceMin));
    }
    return plusProche;
  }
}
