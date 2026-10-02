/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.DistanceGeo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Contrôle de la dispersion géographique des lieux sources, indépendant de leurs noms : une salle
 * dont les lieux sources sont éloignés en mélange plusieurs (contamination), deux salles dont des
 * lieux sources coïncident désignent probablement le même lieu (doublon). Consolide d'abord la
 * position de chaque salle sur celle de ses lieux sources.
 *
 * @since 3.0.2
 * @author Kwaite
 */
final class ControleDispersionGeo {

  /* package-private : visibilité élargie pour les tests (même package). */
  static final double DISTANCE_DISPERSION_METRES = 1000;
  // Au-delà, deux positions désignent des villes différentes quels que soient les codes postaux.
  static final double DISTANCE_AUTRE_VILLE_METRES = 20_000;
  static final double DISTANCE_MEME_LIEU_METRES = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  private record LieuSource(
      UUID salleId, String identifiant, double latitude, double longitude, String codePostal) {}

  ControleDispersionGeo(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * @return nombre de signalements posés
   */
  int verifier() {
    consoliderPositions();
    final List<LieuSource> lieux = chargerLieux();
    return verifierDispersionParSalle(lieux) + verifierSallesAuMemeLieu(lieux);
  }

  // Un mapping déjà accepté par un admin (contamination déclarée faux positif) est ignoré.
  private List<LieuSource> chargerLieux() {
    return jdbc.query(
        """
        SELECT salle_id, source || ':' || identifiant_externe AS identifiant, latitude, longitude,
               code_postal
        FROM salle_sources
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
          AND contamination_acceptee_le IS NULL
        ORDER BY salle_id
        """,
        Map.of(),
        (rs, _) ->
            new LieuSource(
                rs.getObject("salle_id", UUID.class),
                rs.getString("identifiant"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getString("code_postal")));
  }

  private int verifierDispersionParSalle(final List<LieuSource> lieux) {
    final Map<UUID, List<LieuSource>> parSalle = new LinkedHashMap<>();
    lieux.forEach(l -> parSalle.computeIfAbsent(l.salleId(), _ -> new ArrayList<>()).add(l));
    final Set<UUID> dispersees = new HashSet<>();
    for (final Map.Entry<UUID, List<LieuSource>> entree : parSalle.entrySet()) {
      final List<LieuSource> sources = entree.getValue();
      double distanceMax = 0;
      String identifiantA = null;
      String identifiantB = null;
      for (int i = 0; i < sources.size(); i++) {
        for (int j = i + 1; j < sources.size(); j++) {
          final double d = distance(sources.get(i), sources.get(j));
          if (d > distanceMax && lieuxDistincts(sources.get(i), sources.get(j), d)) {
            distanceMax = d;
            identifiantA = sources.get(i).identifiant();
            identifiantB = sources.get(j).identifiant();
          }
        }
      }
      if (distanceMax > DISTANCE_DISPERSION_METRES) {
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
        final LieuSource a = lieux.get(i);
        final LieuSource b = lieux.get(j);
        if (a.salleId().equals(b.salleId()) || distance(a, b) > DISTANCE_MEME_LIEU_METRES) {
          continue;
        }
        final String paire = cle(a.salleId(), b.salleId());
        if (exclues.contains(paire) || !signalees.add(paire)) {
          continue;
        }
        final boolean aPremier = a.salleId().toString().compareTo(b.salleId().toString()) < 0;
        signalements.signaler(
            "meme_lieu_salles",
            "salle",
            aPremier ? a.salleId() : b.salleId(),
            "moyenne",
            Map.of(
                "autreSalleId",
                (aPremier ? b.salleId() : a.salleId()).toString(),
                "distanceMetres",
                Math.round(distance(a, b))));
        entites.add(aPremier ? a.salleId() : b.salleId());
      }
    }
    signalements.clore("meme_lieu_salles", entites);
    return entites.size();
  }

  // Complexe et sous-salle partagent une adresse ; une paire déclarée distincte par un admin
  // n'est plus signalée.
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

  // Une position publiée peut s'écarter de quelques kilomètres du lieu réel : deux positions
  // éloignées ne désignent des lieux distincts que si leurs codes postaux diffèrent aussi, ou
  // si elles sont dans des villes différentes.
  private static boolean lieuxDistincts(final LieuSource a, final LieuSource b, final double d) {
    if (d > DISTANCE_AUTRE_VILLE_METRES) {
      return true;
    }
    return d > DISTANCE_DISPERSION_METRES
        && a.codePostal() != null
        && b.codePostal() != null
        && !a.codePostal().equals(b.codePostal());
  }

  /**
   * Aligne la position de chaque salle sans position Wikidata sur la médiane des positions de ses
   * lieux sources : remplace un géocodage de son nom, parfois celui d'un homonyme lointain.
   * @return nombre de salles repositionnées
   */
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

  private static String cle(final UUID a, final UUID b) {
    return a.toString().compareTo(b.toString()) < 0 ? a + "|" + b : b + "|" + a;
  }

  private static double distance(final LieuSource a, final LieuSource b) {
    return DistanceGeo.metres(a.latitude(), a.longitude(), b.latitude(), b.longitude());
  }
}
