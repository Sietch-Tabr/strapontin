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
 * Mesure, sans rien écrire, l'écart entre le rattachement actuel des lieux sources et celui que
 * donnerait la résolution par position ({@link ResolveurSallePosition}) : à exécuter sur une copie
 * des données de prod avant d'activer cette résolution.
 *
 * <p>Pour chaque mapping {@code salle_sources} géolocalisé :
 *
 * <ul>
 *   <li>{@code conforme} : la résolution par position retient la salle actuelle ;
 *   <li>{@code autreSalle} : elle retient une autre salle existante (doublon de salle probable) ;
 *   <li>{@code salleIncompatible} : la salle actuelle est géolocalisée à plus de {@value
 *       ResolveurSallePosition#DISTANCE_INCOMPATIBLE_METRES} m du lieu (rattachement erroné) ;
 *   <li>{@code indetermine} : aucune salle géolocalisée ne correspond et la salle actuelle n'a pas
 *       de position (repli sur le nom, comme aujourd'hui).
 * </ul>
 *
 * @since 3.0.2
 * @author Kwaite
 */
@Service
public class SimulationResolutionSalles {

  private static final int EXEMPLES_MAX = 50;

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
   * @return effectifs par catégorie et exemples (au plus {@value #EXEMPLES_MAX} par catégorie)
   */
  public Map<String, Object> simuler() {
    final Map<String, Integer> effectifs = new LinkedHashMap<>();
    final Map<String, List<Map<String, Object>>> exemples = new LinkedHashMap<>();
    for (final String categorie :
        List.of("conforme", "autreSalle", "salleIncompatible", "indetermine")) {
      effectifs.put(categorie, 0);
      exemples.put(categorie, new ArrayList<>());
    }
    for (final Map<String, Object> m : chargerMappingsGeolocalises()) {
      final Map<String, Object> exemple = evaluer(m);
      final String categorie = (String) exemple.get("categorie");
      effectifs.merge(categorie, 1, Integer::sum);
      if (!"conforme".equals(categorie) && exemples.get(categorie).size() < EXEMPLES_MAX) {
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

  private Map<String, Object> evaluer(final Map<String, Object> m) {
    final PositionLieu position =
        new PositionLieu(
            (Double) m.get("latitude"), (Double) m.get("longitude"), (String) m.get("code_postal"));
    final UUID salleActuelle = (UUID) m.get("salle_id");
    final String libelle = libelle(m);
    final String dept =
        position.codePostal() != null
            ? Normalisation.extraireDeptFrancais(position.codePostal())
            : (String) m.get("salle_dept");
    final ResolveurSallePosition.SalleProche proche = parPosition.chercher(libelle, dept, position);
    final Double distanceActuelle = distanceSalleActuelle(m, position);

    final String categorie;
    if (proche != null && proche.id().equals(salleActuelle)) {
      categorie = "conforme";
    } else if (distanceActuelle != null
        && distanceActuelle > ResolveurSallePosition.DISTANCE_INCOMPATIBLE_METRES) {
      categorie = "salleIncompatible";
    } else if (proche != null) {
      categorie = "autreSalle";
    } else if (distanceActuelle != null) {
      categorie = "conforme";
    } else {
      categorie = "indetermine";
    }
    final Map<String, Object> exemple = new LinkedHashMap<>();
    exemple.put("categorie", categorie);
    exemple.put("source", m.get("source"));
    exemple.put("identifiant", m.get("identifiant_externe"));
    exemple.put("libelle", libelle);
    exemple.put("salleActuelle", salleActuelle + " " + m.get("salle_nom"));
    exemple.put("distanceSalleActuelleMetres", arrondi(distanceActuelle));
    exemple.put("salleProposee", proche != null ? proche.id().toString() : null);
    exemple.put("distanceSalleProposeeMetres", proche != null ? arrondi(proche.distance()) : null);
    return exemple;
  }

  // Le libellé publié est connu pour Eventim ; à défaut, le nom de la salle rattachée.
  private static String libelle(final Map<String, Object> m) {
    if (m.get("libelle_source") instanceof String libelle) {
      return libelle;
    }
    final String identifiant = (String) m.get("identifiant_externe");
    return identifiant.startsWith(IdentifiantLieuEventim.PREFIXE)
        ? IdentifiantLieuEventim.nom(identifiant)
        : (String) m.get("salle_nom");
  }

  private static Double distanceSalleActuelle(
      final Map<String, Object> m, final PositionLieu position) {
    if (!(m.get("salle_lat") instanceof Double lat)
        || !(m.get("salle_lon") instanceof Double lon)) {
      return null;
    }
    return DistanceGeo.metres(position.latitude(), position.longitude(), lat, lon);
  }

  private static Long arrondi(final Double valeur) {
    return valeur != null ? Math.round(valeur) : null;
  }
}
