/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.DistanceGeo;
import fr.strapontin.core.NomsDeLieu;
import fr.strapontin.core.Normalisation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Dispersion géographique des lieux sources : lieux éloignés d'une salle
 * (contamination ; entre 300 m et 1 km, seulement si leurs noms ne sont pas
 * des doublons forts), lieux de deux salles qui coïncident (doublon). Consolide
 * d'abord la position des salles sur leurs lieux.
 * @since 3.0.2
 * @author Kwaite
 */
final class ControleDispersionGeo {

  /* package-private : visibilité élargie pour les tests (même package). */
  static final double DISTANCE_DISPERSION_METRES = 1000;
  /* package-private : au-delà, deux positions désignent deux villes, quels
   * que soient les codes postaux. */
  static final double DISTANCE_AUTRE_VILLE_METRES = 20_000;
  /* package-private : visibilité élargie pour les tests (même package). */
  static final double DISTANCE_MEME_LIEU_METRES = 50;
  /* package-private : rayon d'un même lieu à la résolution ; au-delà, deux
   * noms qui ne sont pas des doublons forts désignent deux lieux voisins. */
  static final double DISTANCE_LIEUX_VOISINS_METRES = 300;

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  private record LieuSource(
      UUID salleId,
      String identifiant,
      String nom,
      double latitude,
      double longitude,
      String codePostal) {}

  /* package-private : instancié par ControleIntegriteService. */
  ControleDispersionGeo(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * @return nombre de signalements posés
   */
  /* package-private : appelé par ControleIntegriteService. */
  int verifier() {
    consoliderPositions();
    final List<LieuSource> lieux = chargerLieux();
    return verifierDispersionParSalle(lieux)
        + verifierSallesAuMemeLieu(lieux)
        + verifierPositionsWikidata();
  }

  // Position Wikidata loin de tous les lieux sources : élément d'un homonyme
  // (« Espace des Arts » de Chalon pour celui de Pavillons-sous-Bois).
  private int verifierPositionsWikidata() {
    final Map<UUID, Map<String, Object>> incoherentes = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT s.id, s.wikidata_id, s.latitude, s.longitude,
               percentile_cont(0.5) WITHIN GROUP (ORDER BY ss.latitude) AS latitude_sources,
               percentile_cont(0.5) WITHIN GROUP (ORDER BY ss.longitude) AS longitude_sources
        FROM salles s JOIN salle_sources ss ON ss.salle_id = s.id
        WHERE s.origine_position = 'wikidata' AND ss.latitude IS NOT NULL
          AND ss.contamination_acceptee_le IS NULL
        GROUP BY s.id, s.wikidata_id, s.latitude, s.longitude
        """,
        Map.of(),
        rs -> {
          final double distance =
              DistanceGeo.metres(
                  rs.getDouble("latitude"),
                  rs.getDouble("longitude"),
                  rs.getDouble("latitude_sources"),
                  rs.getDouble("longitude_sources"));
          if (distance > DISTANCE_AUTRE_VILLE_METRES) {
            incoherentes.put(
                rs.getObject("id", UUID.class),
                Map.of(
                    "wikidataId", rs.getString("wikidata_id"),
                    "distanceMetres", Math.round(distance)));
          }
        });
    incoherentes.forEach(
        (salle, details) ->
            signalements.signaler(
                "position_wikidata_incoherente", "salle", salle, "haute", details));
    signalements.clore("position_wikidata_incoherente", incoherentes.keySet());
    return incoherentes.size();
  }

  // Mapping accepté par un admin (faux positif de contamination) : ignoré.
  private List<LieuSource> chargerLieux() {
    return jdbc.query(
        """
        SELECT ss.salle_id, ss.source || ':' || ss.identifiant_externe AS identifiant,
               COALESCE(ss.libelle_source,
                        CASE WHEN ss.identifiant_externe LIKE 'internal:%'
                             THEN split_part(substr(ss.identifiant_externe, 10), '@', 1) END,
                        s.nom) AS nom,
               ss.latitude, ss.longitude, ss.code_postal
        FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
        WHERE ss.latitude IS NOT NULL AND ss.longitude IS NOT NULL
          AND ss.contamination_acceptee_le IS NULL
        ORDER BY ss.salle_id
        """,
        Map.of(),
        (rs, _) ->
            new LieuSource(
                rs.getObject("salle_id", UUID.class),
                rs.getString("identifiant"),
                Normalisation.normaliserTexte(rs.getString("nom")),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getString("code_postal")));
  }

  private int verifierDispersionParSalle(final List<LieuSource> lieux) {
    final Map<UUID, List<LieuSource>> parSalle = new LinkedHashMap<>();
    lieux.forEach(
        lieu -> parSalle.computeIfAbsent(lieu.salleId(), _ -> new ArrayList<>()).add(lieu));
    final Set<UUID> dispersees = new HashSet<>();
    for (final Map.Entry<UUID, List<LieuSource>> entree : parSalle.entrySet()) {
      final List<LieuSource> sources = entree.getValue();
      double distanceMax = 0;
      String identifiantA = null;
      String identifiantB = null;
      for (int i = 0; i < sources.size(); i++) {
        for (int j = i + 1; j < sources.size(); j++) {
          final double ecart = distance(sources.get(i), sources.get(j));
          if (ecart > distanceMax && lieuxDistincts(sources.get(i), sources.get(j), ecart)) {
            distanceMax = ecart;
            identifiantA = sources.get(i).identifiant();
            identifiantB = sources.get(j).identifiant();
          }
        }
      }
      if (identifiantA != null) {
        signalements.signaler(
            "dispersion_sources_salle",
            "salle",
            entree.getKey(),
            "haute",
            Map.of(
                "distanceMetres", Math.round(distanceMax),
                "sourceA", identifiantA,
                "sourceB", identifiantB));
        dispersees.add(entree.getKey());
      }
    }
    signalements.clore("dispersion_sources_salle", dispersees);
    return dispersees.size();
  }

  private int verifierSallesAuMemeLieu(final List<LieuSource> lieux) {
    final Set<String> exclues = chargerPairesExclues();
    final Set<String> signalees = new HashSet<>();
    final Set<UUID> entites = new HashSet<>();
    for (int i = 0; i < lieux.size(); i++) {
      for (int j = i + 1; j < lieux.size(); j++) {
        final LieuSource premier = lieux.get(i);
        final LieuSource second = lieux.get(j);
        if (deuxSallesAuMemeLieu(premier, second)
            && !exclues.contains(cle(premier.salleId(), second.salleId()))
            && signalees.add(cle(premier.salleId(), second.salleId()))) {
          entites.add(signalerMemeLieu(premier, second));
        }
      }
    }
    signalements.clore("meme_lieu_salles", entites);
    return entites.size();
  }

  private static boolean deuxSallesAuMemeLieu(final LieuSource premier, final LieuSource second) {
    return !premier.salleId().equals(second.salleId())
        && distance(premier, second) <= DISTANCE_MEME_LIEU_METRES;
  }

  // Signalement porté par la salle d'UUID le plus petit, l'autre en détail.
  private UUID signalerMemeLieu(final LieuSource premier, final LieuSource second) {
    final boolean ordre = premier.salleId().toString().compareTo(second.salleId().toString()) < 0;
    final UUID porteuse = ordre ? premier.salleId() : second.salleId();
    final UUID autre = ordre ? second.salleId() : premier.salleId();
    signalements.signaler(
        "meme_lieu_salles",
        "salle",
        porteuse,
        "moyenne",
        Map.of(
            "autreSalleId", autre.toString(),
            "distanceMetres", Math.round(distance(premier, second))));
    return porteuse;
  }

  // Complexe et sous-salle partagent une adresse ; une paire déclarée
  // distincte par un admin n'est plus signalée.
  private Set<String> chargerPairesExclues() {
    final Set<String> exclues = new HashSet<>();
    jdbc.query(
        """
        SELECT id AS a, salle_parent_id AS b FROM salles WHERE salle_parent_id IS NOT NULL
        UNION ALL
        SELECT salle_a, salle_b FROM salles_paires_distinctes
        """,
        Map.of(),
        rs -> {
          exclues.add(cle(rs.getObject("a", UUID.class), rs.getObject("b", UUID.class)));
        });
    return exclues;
  }

  // Une position publiée s'écarte de quelques km : lieux distincts si les
  // codes postaux diffèrent aussi, ou s'ils sont dans deux villes. Sous 1 km,
  // deux noms sans doublon fort (le mot pivot rapprochait Galabru et
  // l'Élysée Montmartre) désignent deux lieux voisins.
  private static boolean lieuxDistincts(
      final LieuSource premier, final LieuSource second, final double ecart) {
    final boolean codesPostauxDifferents =
        premier.codePostal() != null
            && second.codePostal() != null
            && !premier.codePostal().equals(second.codePostal());
    return ecart > DISTANCE_AUTRE_VILLE_METRES
        || ecart > DISTANCE_DISPERSION_METRES && codesPostauxDifferents
        || ecart > DISTANCE_LIEUX_VOISINS_METRES
            && ecart <= DISTANCE_DISPERSION_METRES
            && !NomsDeLieu.designentFortementLeMemeLieu(premier.nom(), second.nom());
  }

  /**
   * Position des salles sans Wikidata alignée sur la médiane de leurs lieux
   * sources (remplace un géocodage, parfois celui d'un homonyme lointain).
   * @return nombre de salles repositionnées
   */
  /* package-private : appelé par verifier() et les tests. */
  int consoliderPositions() {
    return jdbc.update(
        """
        UPDATE salles s
        SET latitude = m.latitude, longitude = m.longitude, origine_position = 'sources'
        FROM (SELECT salle_id,
                     percentile_cont(0.5) WITHIN GROUP (ORDER BY latitude) AS latitude,
                     percentile_cont(0.5) WITHIN GROUP (ORDER BY longitude) AS longitude
              FROM salle_sources
              WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                AND contamination_acceptee_le IS NULL
              GROUP BY salle_id) m
        WHERE s.id = m.salle_id
          AND (s.origine_position IS NULL OR s.origine_position IN ('sources', 'geocodage'))
          AND (s.latitude IS DISTINCT FROM m.latitude OR s.longitude IS DISTINCT FROM m.longitude)
        """,
        Map.of());
  }

  private static String cle(final UUID salleA, final UUID salleB) {
    return salleA.toString().compareTo(salleB.toString()) < 0
        ? salleA + "|" + salleB
        : salleB + "|" + salleA;
  }

  private static double distance(final LieuSource premier, final LieuSource second) {
    return DistanceGeo.metres(
        premier.latitude(), premier.longitude(), second.latitude(), second.longitude());
  }
}
