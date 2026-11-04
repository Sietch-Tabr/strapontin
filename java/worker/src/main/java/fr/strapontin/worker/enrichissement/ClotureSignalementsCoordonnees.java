/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.enrichissement;

import fr.strapontin.worker.sante.SignalementMatchingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Referme les signalements d'écart de coordonnées Wikidata des salles qui
 * portent désormais un élément Wikidata ou n'existent plus : une salle sans
 * élément reste concernée, son écart peut persister.
 * @since 3.1.3
 * @author Kwaite
 */
final class ClotureSignalementsCoordonnees {

  /* package-private : partagé avec EnrichissementSallesService. */
  static final String TYPE = "wikidata_coordonnees_divergentes";

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  /* package-private : instancié par EnrichissementSallesService. */
  ClotureSignalementsCoordonnees(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /* package-private : appelé par EnrichissementSallesService. */
  void clore() {
    final List<UUID> encoreConcernees =
        jdbc.queryForList(
            """
            SELECT sm.entite_id FROM signalements_matching sm
            JOIN salles s ON s.id = sm.entite_id
            WHERE sm.type = :type AND sm.resolu_le IS NULL AND s.wikidata_id IS NULL
            """,
            Map.of("type", TYPE),
            UUID.class);
    signalements.clore(TYPE, encoreConcernees);
  }
}
