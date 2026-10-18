/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Sources suspectes des salles d'une page du référentiel : de quoi expliquer
 * un statut « À vérifier » ou « Invalide ».
 * @since 3.0.6
 * @author Kwaite
 */
final class SuspicionsSalles {

  private SuspicionsSalles() {}

  /**
   * Seules les salles dont le statut n'est pas « à jour » ont une source suspecte à expliquer.
   *
   * @param jdbc accès JDBC
   * @param salles lignes de la page
   * @return les mêmes lignes, enrichies de leurs sources suspectes
   */
  /* package-private : appelé par ReferentielSallesService (même package). */
  static List<SalleReferentiel> ajouter(
      final NamedParameterJdbcTemplate jdbc, final List<SalleReferentiel> salles) {
    final List<UUID> suspectes =
        salles.stream()
            .filter(s -> !"a_jour".equals(s.statut()))
            .map(SalleReferentiel::id)
            .toList();
    if (suspectes.isEmpty()) {
      return salles;
    }
    final Map<UUID, List<SalleReferentiel.SuspicionSource>> parSalle = new HashMap<>();
    jdbc.query(
        "SELECT salle_id, source, nb_zero_consecutifs, suspect_depuis FROM salle_sources"
            + " WHERE salle_id IN (:ids) AND suspect_depuis IS NOT NULL ORDER BY suspect_depuis",
        Map.of("ids", suspectes),
        rs -> {
          parSalle
              .computeIfAbsent(rs.getObject("salle_id", UUID.class), _ -> new ArrayList<>())
              .add(
                  new SalleReferentiel.SuspicionSource(
                      rs.getString("source"),
                      rs.getInt("nb_zero_consecutifs"),
                      rs.getObject("suspect_depuis", OffsetDateTime.class).toInstant()));
        });
    return salles.stream()
        .map(
            s ->
                new SalleReferentiel(
                    s.id(),
                    s.nom(),
                    s.nomAffiche(),
                    s.ville(),
                    s.dept(),
                    s.nbConcerts(),
                    s.sources(),
                    s.statut(),
                    s.wikidataId(),
                    s.nomParent(),
                    parSalle.getOrDefault(s.id(), List.of()),
                    s.lieuEventim()))
        .toList();
  }
}
