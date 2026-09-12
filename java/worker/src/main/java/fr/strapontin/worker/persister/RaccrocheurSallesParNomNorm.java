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
 * Rattache à leur salle les concerts sans {@code salleId}, ou réaligne ceux dont le {@code salleId}
 * déjà résolu pointe vers une salle dont le {@code nom_norm} diverge désormais du {@code salleNorm}
 * du concert.
 *
 * <p>Extrait de {@link ResolveurSalleParConcert} pour rester sous la limite de 500 lignes par
 * fichier — via correspondance exacte sur {@code salle_norm} / {@code nom_norm}.
 *
 * <p>(rattrapage) : une simple égalité de {@code nom_norm} ignorait le département, défaisant
 * silencieusement le filtre dept de {@link ResolveurSalleParConcert#chercherSalleParTokens} pour
 * deux salles homonymes au texte strictement identique (ex. "Casino - Barriere" Toulouse/Lille,
 * {@code chercherSalleParTokens} rejette correctement le candidat en amont, mais ce repli par
 * égalité exacte le retrouvait quand même en aval, sans filtre). Même repli sur le département d'un
 * concert déjà rattaché que {@code chercherSalleParTokens} quand {@code salles.dept} est vide.
 *
 * <p>Le réalignement couvre le cas d'un concert résolu par nom avant réconciliation (ex. une fiche
 * Eventim sans indication de sous-salle, résolue au complexe parent) dont le {@code salleNorm}
 * converge ensuite, via {@code reconcilierSalles}, vers celui d'une sous-salle déjà établie — sans
 * ce réalignement, le {@code salleId} restait celui du complexe, créant un événement cross-source
 * distinct pour la même date/artiste. Une correspondance exacte de {@code nom_norm} n'existant que
 * pour une salle réellement homonyme, ce réalignement laisse inchangés les concerts résolus par clé
 * forte sur un nom sans rapport ({@link ResolveurSalleParConcert#chercherSalleParCleForte} — leur
 * {@code salleNorm} ne partage par construction aucune correspondance exacte en base).
 *
 * @since 2.9.1
 * @author Kwaite
 */
final class RaccrocheurSallesParNomNorm {

  private final NamedParameterJdbcTemplate jdbc;

  RaccrocheurSallesParNomNorm(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param concerts concerts après déduplication
   * @return liste enrichie avec les {@code salleId} résolus ou réalignés par nom_norm
   */
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
   * @return {@code true} si le concert n'a pas encore de {@code salleId}, ou si celui déjà résolu
   *     pointe vers une salle dont le {@code nom_norm} connu diverge du {@code salleNorm} actuel du
   *     concert (silencieux si le {@code nom_norm} de la salle résolue n'est pas connu — pas de
   *     réalignement sans confirmation positive de la divergence)
   */
  private static boolean salleIdDivergent(
      final ValeurConcert c, final Map<UUID, String> nomNormParSalleId) {
    if (c.salleId() == null) {
      return true;
    }
    final String nomNormActuel = nomNormParSalleId.get(c.salleId());
    return nomNormActuel != null && !nomNormActuel.equals(c.salleNorm());
  }

  /**
   * Charge en une seule requête le {@code nom_norm} connu de chaque salle donnée.
   *
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
