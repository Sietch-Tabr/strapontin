/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.NomsDeLieu;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import fr.strapontin.core.QualificatifSalle;
import fr.strapontin.core.ReglesCommunes;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Salle d'un lieu par sa position publiée : à moins de 300 m, même
 * département, nom doublon. Veto d'une salle trouvée par son nom à plus de
 * 20 km (homonyme), ou entre 300 m et 1 km sans nom commun (lieu voisin).
 * @since 3.0.2
 * @author Kwaite
 */
final class ResolveurSallePosition {

  /* package-private : positions publiées décalées jusqu'à 7 km (centre-ville
   * pour une salle de périphérie) : seul un autre lieu urbain est exclu. */
  static final double DISTANCE_INCOMPATIBLE_METRES = 20_000;
  // Au-delà, une position publiée imprécise (salle de périphérie) n'est plus
  // un signal de lieu voisin : seul le veto à 20 km s'applique.
  private static final double DISTANCE_LIEUX_VOISINS_MAX_METRES = 1000;
  // Rayon en degrés du préfiltre SQL (au plus ~0,005° pour 300 m en
  // France métropolitaine).
  private static final double DEGRES_PREFILTRE = 0.005;

  private final NamedParameterJdbcTemplate jdbc;

  /** Salle candidate et sa distance au lieu, en mètres. */
  /* package-private : résultat de chercher(). */
  record SalleProche(UUID id, double distance) {}

  /* package-private : instancié par ResolveurSalleParConcert et la simulation. */
  ResolveurSallePosition(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param nom nom brut du lieu publié
   * @param dept département du lieu, peut être null
   * @param position position publiée du lieu
   * @return la salle la plus proche désignant le même lieu, ou null
   */
  /* package-private : appelé par ResolveurSalleParConcert et la simulation. */
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
      if (distance > ReglesCommunes.RAYON_MEME_LIEU_METRES
          || !CorrespondanceSalle.estDoublonSalle(nomNorm, (String) c.get("nom_norm"))
          || QualificatifSalle.qualificatifsDivergents(nom, (String) c.get("nom"))) {
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
   * @param nom nom brut du lieu publié
   * @param position position publiée du lieu
   * @return true si salle et lieu, géolocalisés, sont trop éloignés pour
   *     être le même lieu (voir la classe), ou si leurs codes postaux connus
   *     diffèrent sans que les noms soient identiques
   */
  /* package-private : appelé par ResolveurSalleParConcert et la simulation. */
  boolean incompatible(final UUID salleId, final String nom, final PositionLieu position) {
    return salleId != null && codePostalDifferent(salleId, nom, position)
        || tropEloignee(salleId, nom, position);
  }

  /**
   * Veto de distance seul, sans code postal : salle déjà retenue sur artiste,
   * date, heure et département.
   * @param salleId salle candidate, peut être null
   * @param nom nom brut du lieu publié
   * @param position position publiée du lieu
   * @return true si salle et lieu, géolocalisés, sont trop éloignés
   */
  /* package-private : appelé par ResolveurSalleParConcert. */
  boolean tropEloignee(final UUID salleId, final String nom, final PositionLieu position) {
    if (salleId == null || !position.estGeolocalisee()) {
      return false;
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            // Position fiable seulement : tirée des lieux publiés, ou Wikidata
            // confirmée par un lieu positionné. Un géocodage du nom ou un
            // élément Wikidata homonyme écartait la bonne salle du département.
            """
            SELECT latitude, longitude, nom_norm, concat_ws(' ', ville, nom) AS contexte FROM salles s
            WHERE s.id = CAST(:id AS uuid) AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
              AND (s.origine_position = 'sources'
                   OR s.origine_position = 'wikidata' AND EXISTS (
                       SELECT 1 FROM salle_sources ss
                       WHERE ss.salle_id = s.id AND ss.latitude IS NOT NULL))
            """,
            Map.of("id", salleId.toString()));
    if (lignes.isEmpty()) {
      return false;
    }
    final double distance =
        DistanceGeo.metres(
            position.latitude(),
            position.longitude(),
            (Double) lignes.get(0).get("latitude"),
            (Double) lignes.get(0).get("longitude"));
    return distance > DISTANCE_INCOMPATIBLE_METRES
        || distance > ReglesCommunes.RAYON_MEME_LIEU_METRES
            && distance <= DISTANCE_LIEUX_VOISINS_MAX_METRES
            && !NomsDeLieu.designentFortementLeMemeLieu(
                Normalisation.normaliserTexte(nom),
                (String) lignes.get(0).get("nom_norm"),
                (String) lignes.get(0).get("contexte"));
  }

  // Deux codes postaux connus et différents désignent deux lieux : un nom
  // contenu dans un autre (« dome » dans « vendome ») ne suffit pas. Un nom
  // identique est gardé, les billetteries divergeant parfois sur le code postal
  // d'un même lieu.
  private boolean codePostalDifferent(
      final UUID salleId, final String nom, final PositionLieu position) {
    if (position.codePostal() == null) {
      return false;
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT code_postal, nom_norm FROM salles WHERE id = CAST(:id AS uuid)"
                + " AND code_postal IS NOT NULL",
            Map.of("id", salleId.toString()));
    return !lignes.isEmpty()
        && !position.codePostal().equals(lignes.get(0).get("code_postal"))
        && !Normalisation.normaliserTexte(nom).equals(lignes.get(0).get("nom_norm"));
  }
}
