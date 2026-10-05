/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.IdentifiantLieuEventim;
import fr.strapontin.core.Normalisation;
import fr.strapontin.core.PositionLieu;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Mesure, sans écrire, l'écart entre le rattachement actuel des lieux et la
 * résolution par position : conforme, autre salle (doublon probable), salle
 * incompatible (trop loin), ou indéterminé (aucune position de part et d'autre).
 * @since 3.0.2
 * @author Kwaite
 */
@Service
public class SimulationResolutionSalles {

  private static final int EXEMPLES_MAX = 50;

  private static final String CONFORME = "conforme";
  private static final String AUTRE_SALLE = "autreSalle";
  private static final String SALLE_INCOMPATIBLE = "salleIncompatible";
  private static final String INDETERMINE = "indetermine";

  private final NamedParameterJdbcTemplate jdbc;
  private final ResolveurSallePosition parPosition;

  /**
   * @param jdbc accès JDBC, en lecture seule
   */
  public SimulationResolutionSalles(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.parPosition = new ResolveurSallePosition(jdbc);
  }

  /**
   * @return effectifs par catégorie et exemples ({@value #EXEMPLES_MAX} au
   *     plus par catégorie)
   */
  public Map<String, Object> simuler() {
    final Map<String, Integer> effectifs = new LinkedHashMap<>();
    final Map<String, List<Map<String, Object>>> exemples = new LinkedHashMap<>();
    for (final String categorie : List.of(CONFORME, AUTRE_SALLE, SALLE_INCOMPATIBLE, INDETERMINE)) {
      effectifs.put(categorie, 0);
      exemples.put(categorie, new ArrayList<>());
    }
    for (final Map<String, Object> mapping : chargerMappingsGeolocalises()) {
      final Map<String, Object> exemple = evaluer(mapping);
      final String categorie = (String) exemple.get("categorie");
      effectifs.merge(categorie, 1, Integer::sum);
      if (!CONFORME.equals(categorie) && exemples.get(categorie).size() < EXEMPLES_MAX) {
        exemples.get(categorie).add(exemple);
      }
    }
    final Map<String, Object> rapport = new LinkedHashMap<>();
    rapport.put("effectifs", effectifs);
    rapport.put("exemples", exemples);
    return rapport;
  }

  private List<Map<String, Object>> chargerMappingsGeolocalises() {
    return jdbc.queryForList(
        """
        SELECT ss.source, ss.identifiant_externe, ss.libelle_source, ss.latitude, ss.longitude,
               ss.code_postal, s.id AS salle_id, s.nom AS salle_nom, s.dept AS salle_dept,
               s.latitude AS salle_lat, s.longitude AS salle_lon
        FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
        WHERE ss.latitude IS NOT NULL AND ss.longitude IS NOT NULL
        ORDER BY ss.source, ss.identifiant_externe
        """,
        Map.of());
  }

  private Map<String, Object> evaluer(final Map<String, Object> mapping) {
    final PositionLieu position =
        new PositionLieu(
            (Double) mapping.get("latitude"),
            (Double) mapping.get("longitude"),
            (String) mapping.get("code_postal"));
    final UUID salleActuelle = (UUID) mapping.get("salle_id");
    final String libelle = libelle(mapping);
    final String dept =
        position.codePostal() != null
            ? Normalisation.extraireDeptFrancais(position.codePostal())
            : (String) mapping.get("salle_dept");
    final ResolveurSallePosition.SalleProche proche = parPosition.chercher(libelle, dept, position);
    final Double distanceActuelle = distanceSalleActuelle(mapping, position);

    final String categorie = categorie(proche, salleActuelle, distanceActuelle);
    final Map<String, Object> exemple = new LinkedHashMap<>();
    exemple.put("categorie", categorie);
    exemple.put("source", mapping.get("source"));
    exemple.put("identifiant", mapping.get("identifiant_externe"));
    exemple.put("libelle", libelle);
    exemple.put("salleActuelle", salleActuelle + " " + mapping.get("salle_nom"));
    exemple.put("distanceSalleActuelleMetres", arrondi(distanceActuelle));
    exemple.put("salleProposee", proche != null ? proche.id().toString() : null);
    exemple.put("distanceSalleProposeeMetres", proche != null ? arrondi(proche.distance()) : null);
    return exemple;
  }

  private static String categorie(
      final ResolveurSallePosition.SalleProche proche,
      final UUID salleActuelle,
      final Double distanceActuelle) {
    if (proche != null && proche.id().equals(salleActuelle)) {
      return CONFORME;
    }
    if (distanceActuelle != null
        && distanceActuelle > ResolveurSallePosition.DISTANCE_INCOMPATIBLE_METRES) {
      return SALLE_INCOMPATIBLE;
    }
    if (proche != null) {
      return AUTRE_SALLE;
    }
    return distanceActuelle != null ? CONFORME : INDETERMINE;
  }

  // Libellé publié connu pour Eventim ; à défaut, nom de la salle rattachée.
  private static String libelle(final Map<String, Object> mapping) {
    if (mapping.get("libelle_source") instanceof String libelle) {
      return libelle;
    }
    final String identifiant = (String) mapping.get("identifiant_externe");
    return identifiant.startsWith(IdentifiantLieuEventim.PREFIXE)
        ? IdentifiantLieuEventim.nom(identifiant)
        : (String) mapping.get("salle_nom");
  }

  private static Double distanceSalleActuelle(
      final Map<String, Object> mapping, final PositionLieu position) {
    if (!(mapping.get("salle_lat") instanceof Double lat)
        || !(mapping.get("salle_lon") instanceof Double lon)) {
      return null;
    }
    return DistanceGeo.metres(position.latitude(), position.longitude(), lat, lon);
  }

  private static Long arrondi(final Double valeur) {
    return valeur != null ? Math.round(valeur) : null;
  }
}
