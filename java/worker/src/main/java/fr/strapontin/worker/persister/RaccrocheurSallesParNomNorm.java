/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Rattache à leur salle les concerts sans {@code salleId}, ou
 * réaligne ceux dont le {@code salleId} déjà résolu diverge du
 * {@code salleNorm} actuel (détail : CLAUDE.md, Hiérarchie des
 * salles).
 * @since 2.9.1
 * @author Kwaite
 */
final class RaccrocheurSallesParNomNorm {

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par ReconciliateurSalles (même
   * package). */
  RaccrocheurSallesParNomNorm(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param concerts concerts après déduplication
   * @return liste enrichie avec les salleId résolus ou réalignés
   */
  /* package-private : appelé par ReconciliateurSalles (même package). */
  List<ValeurConcert> raccrocher(final List<ValeurConcert> concerts) {
    final Set<UUID> salleIdsExistants = new HashSet<>();
    for (final ValeurConcert c : concerts) {
      if (c.salleId() != null) {
        salleIdsExistants.add(c.salleId());
      }
    }
    final Map<UUID, String> nomNormParSalleId = nomNormConnus(salleIdsExistants);

    final List<String> sallesNormsARattacher =
        concerts.stream()
            .filter(c -> salleIdDivergent(c, nomNormParSalleId))
            .map(ValeurConcert::salleNorm)
            .distinct()
            .toList();
    if (sallesNormsARattacher.isEmpty()) {
      return concerts;
    }

    final Map<String, List<Map<String, Object>>> candidatsParNomNorm = new HashMap<>();
    jdbc.queryForList(
            "SELECT s.nom_norm AS nom_norm, s.id AS id,"
                + " COALESCE(s.dept, (SELECT c.dept FROM concerts c"
                + "                   WHERE c.salle_id = s.id AND c.dept IS NOT NULL LIMIT 1))"
                + "   AS dept_effectif"
                + " FROM salles s WHERE s.nom_norm IN (:normsNoms)",
            Map.of("normsNoms", sallesNormsARattacher))
        .forEach(
            r ->
                candidatsParNomNorm
                    .computeIfAbsent((String) r.get("nom_norm"), _ -> new ArrayList<>())
                    .add(r));

    return concerts.stream()
        .map(
            c -> {
              if (!salleIdDivergent(c, nomNormParSalleId)) {
                return c;
              }
              final List<Map<String, Object>> candidats = candidatsParNomNorm.get(c.salleNorm());
              if (candidats == null) {
                return c;
              }
              for (final Map<String, Object> candidat : candidats) {
                final String deptEffectif = (String) candidat.get("dept_effectif");
                if (c.dept() == null || deptEffectif == null || deptEffectif.equals(c.dept())) {
                  return c.avecSalleId((UUID) candidat.get("id"));
                }
              }
              return c;
            })
        .toList();
  }

  /**
   * @return {@code true} si non résolu, ou si le {@code nom_norm}
   * connu de la salle résolue diverge du {@code salleNorm} actuel
   * (silencieux si {@code nom_norm} inconnu).
   */
  private static boolean salleIdDivergent(
      final ValeurConcert concert, final Map<UUID, String> nomNormParSalleId) {
    if (concert.salleId() == null) {
      return true;
    }
    final String nomNormActuel = nomNormParSalleId.get(concert.salleId());
    return nomNormActuel != null && !nomNormActuel.equals(concert.salleNorm());
  }

  /**
   * Charge en une seule requête le {@code nom_norm} connu de chaque
   * salle donnée.
   * @param salleIds identifiants des salles à vérifier
   * @return {@code nom_norm} par salle
   */
  private Map<UUID, String> nomNormConnus(final Set<UUID> salleIds) {
    if (salleIds.isEmpty()) {
      return Map.of();
    }
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            "SELECT id, nom_norm FROM salles WHERE id IN (:ids)", Map.of("ids", salleIds));
    final Map<UUID, String> resultat = new HashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      resultat.put((UUID) ligne.get("id"), (String) ligne.get("nom_norm"));
    }
    return resultat;
  }
}
