/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.worker.LogsAdminWorker;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Doublons de salles potentiels (noms qui se contiennent, département
 * compatible), décisions admin et levée des signalements caducs — extrait
 * de {@link CoherenceMultiSalleService} (taille).
 * @since 3.0.1
 * @author Kwaite
 */
final class DetectionDoublonsSalles {

  private static final Logger LOG = LoggerFactory.getLogger(DetectionDoublonsSalles.class);
  private static final String CLE_DOUBLON = "doublon_signale";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /* package-private : instancié par CoherenceMultiSalleService (même package). */
  DetectionDoublonsSalles(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Balayage pairwise par containment de {@code nom_norm} et département
   * compatible, sans exiger de {@code wikidata_id} ni de concert partagé
   * (détail : CLAUDE.md, Hiérarchie des salles).
   * @return nombre de paires nouvellement signalées
   */
  /* package-private : appelé par CoherenceMultiSalleService (même package). */
  int detecter() {
    final List<Map<String, Object>> salles =
        jdbc.queryForList(
            "SELECT id, nom_norm, dept, doublon_signale, salle_parent_id FROM salles ORDER BY id",
            Map.of());
    final Set<String> distinctes = chargerPairesDistinctes();
    leverSignalementsCaducs(salles, distinctes);

    int signalees = 0;
    for (int i = 0; i < salles.size(); i++) {
      for (int j = i + 1; j < salles.size(); j++) {
        if (evaluerPaireDoublon(salles.get(i), salles.get(j), distinctes)) {
          signalees++;
        }
      }
    }
    LOG.info("coherence:multi-salle:doublon salles={} signalees={}", salles.size(), signalees);
    return signalees;
  }

  private Set<String> chargerPairesDistinctes() {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT salle_a::text || '|' || salle_b::text FROM salles_paires_distinctes",
            Map.of(),
            String.class));
  }

  // Ordre textuel : c'est celui de PostgreSQL sur uuid (CHECK salle_a < salle_b),
  // pas celui de UUID.compareTo (comparaison de long signés).
  private static String clePaire(final UUID idA, final UUID idB) {
    final String premier = idA.toString();
    final String second = idB.toString();
    return premier.compareTo(second) < 0 ? premier + "|" + second : second + "|" + premier;
  }

  /**
   * Lève un signalement de doublon dont la paire ne remplit plus les
   * conditions (sous-salle rattachée depuis, salles jugées distinctes par un
   * admin) — sans quoi il reste affiché indéfiniment.
   */
  private void leverSignalementsCaducs(
      final List<Map<String, Object>> salles, final Set<String> distinctes) {
    final Map<UUID, Map<String, Object>> parId = new HashMap<>();
    salles.forEach(salle -> parId.put((UUID) salle.get("id"), salle));
    for (final Map<String, Object> salle : salles) {
      final UUID partenaire = (UUID) salle.get(CLE_DOUBLON);
      if (partenaire != null
          && (!parId.containsKey(partenaire)
              || !estPaireCandidate(salle, parId.get(partenaire), distinctes))) {
        jdbc.update(
            "UPDATE salles SET doublon_signale = NULL WHERE id = CAST(:id AS uuid)",
            Map.of("id", salle.get("id").toString()));
        salle.put(CLE_DOUBLON, null);
      }
    }
  }

  /**
   * @return {@code true} si la paire est un doublon potentiel : ni un
   *     complexe et sa sous-salle, ni de départements incompatibles, ni jugée
   *     distincte par un admin, et des noms dont l'un contient l'autre.
   */
  private static boolean estPaireCandidate(
      final Map<String, Object> salleA,
      final Map<String, Object> salleB,
      final Set<String> distinctes) {
    final UUID idA = (UUID) salleA.get("id");
    final UUID idB = (UUID) salleB.get("id");
    final UUID parentA = (UUID) salleA.get("salle_parent_id");
    final UUID parentB = (UUID) salleB.get("salle_parent_id");
    // Un complexe et sa sous-salle déjà reliée ne sont jamais des doublons —
    // leur nom qualifié ("X - Y") satisferait sinon le containment de tokens
    // ci-dessous par construction.
    if (idA.equals(parentB) || idB.equals(parentA) || distinctes.contains(clePaire(idA, idB))) {
      return false;
    }
    final String deptA = (String) salleA.get("dept");
    final String deptB = (String) salleB.get("dept");
    if (deptA != null && deptB != null && !deptA.equals(deptB)) {
      return false;
    }
    return CoherenceMultiSalleService.nomContenuDansLAutre(
        (String) salleA.get("nom_norm"), (String) salleB.get("nom_norm"));
  }

  /**
   * @return {@code true} si la paire est un doublon potentiel nouvellement
   *     signalé.
   */
  private boolean evaluerPaireDoublon(
      final Map<String, Object> salleA,
      final Map<String, Object> salleB,
      final Set<String> distinctes) {
    if (!estPaireCandidate(salleA, salleB, distinctes)) {
      return false;
    }
    final UUID idB = (UUID) salleB.get("id");
    if (idB.equals(salleA.get(CLE_DOUBLON))) {
      return false;
    }
    final UUID idA = (UUID) salleA.get("id");

    memoriserDoublonSignale(idA, idB);
    salleA.put(CLE_DOUBLON, idB);
    logsAdminWorker.inserer(
        "warn",
        "coherence-salle",
        "doublon de salle potentiel sans wikidata_id ni concert partagé salleA="
            + idA
            + " salleB="
            + idB,
        Map.of("salleA", idA.toString(), "salleB", idB.toString()));
    return true;
  }

  private void memoriserDoublonSignale(final UUID idA, final UUID idB) {
    jdbc.update(
        "UPDATE salles SET doublon_signale = CAST(:idB AS uuid) WHERE id = CAST(:idA AS uuid)",
        new MapSqlParameterSource()
            .addValue("idA", idA.toString())
            .addValue("idB", idB.toString()));
  }
}
