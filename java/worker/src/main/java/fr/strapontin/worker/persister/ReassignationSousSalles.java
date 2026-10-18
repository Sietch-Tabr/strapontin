/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.SalleHierarchie;
import fr.strapontin.worker.LogsAdminWorker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Sous-salles d'une salle absorbée par une fusion : réassignées à la salle
 * conservée, sans jamais créer de cycle de parenté.
 * @since 3.0.6
 * @author Kwaite
 */
final class ReassignationSousSalles {

  private static final String CLE_SOURCE = "source";
  private static final String CLE_CIBLE = "cible";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogsAdminWorker logsAdminWorker;

  /* package-private : instancié par FusionSallesClePorteeService (même package). */
  ReassignationSousSalles(
      final NamedParameterJdbcTemplate jdbc, final LogsAdminWorker logsAdminWorker) {
    this.jdbc = jdbc;
    this.logsAdminWorker = logsAdminWorker;
  }

  /**
   * Réassigne à la cible chaque sous-salle de la source (hors cible
   * elle-même, déjà traitée), sauf celle dont la cible est déjà un
   * descendant — la réassigner créerait un cycle enfant → cible → ... →
   * enfant (#1201). Une telle sous-salle est détachée (parent à NULL)
   * plutôt que laissée pointer vers la source supprimée, et signalée pour
   * revue manuelle (cas limite non exclu par le seul cas direct géré).
   */
  void reassignerSansCycle(final UUID cibleId, final UUID sourceId) {
    final List<UUID> enfants =
        jdbc.query(
            "SELECT id FROM salles WHERE salle_parent_id = CAST(:source AS uuid) AND id !="
                + " CAST(:cible AS uuid)",
            Map.of(CLE_SOURCE, sourceId.toString(), CLE_CIBLE, cibleId.toString()),
            (rs, _) -> rs.getObject("id", UUID.class));

    for (final UUID enfant : enfants) {
      if (SalleHierarchie.creeraitCycle(cibleId, enfant, this::lireSalleParentId)) {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = NULL WHERE id = CAST(:id AS uuid)",
            Map.of("id", enfant.toString()));
        logsAdminWorker.inserer(
            "warn",
            "reconciliation-nocturne",
            "cycle salle_parent_id évité lors d'une fusion — sous-salle détachée enfant="
                + enfant
                + " cible="
                + cibleId,
            Map.of("enfant", enfant.toString(), CLE_CIBLE, cibleId.toString()));
      } else {
        jdbc.update(
            "UPDATE salles SET salle_parent_id = CAST(:cible AS uuid) WHERE id = CAST(:id AS"
                + " uuid)",
            Map.of(CLE_CIBLE, cibleId.toString(), "id", enfant.toString()));
      }
    }
  }

  // findFirst() sur un flux contenant un unique élément null lève une
  // NullPointerException (Optional.of appelé en interne) — un accès direct à la
  // liste évite ce piège pour une salle racine sans parent.
  private UUID lireSalleParentId(final UUID salleId) {
    final List<UUID> resultat =
        jdbc.query(
            "SELECT salle_parent_id FROM salles WHERE id = CAST(:id AS uuid)",
            Map.of("id", salleId.toString()),
            (rs, _) -> (UUID) rs.getObject("salle_parent_id"));
    return resultat.isEmpty() ? null : resultat.get(0);
  }
}
