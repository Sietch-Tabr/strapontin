/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.sources.PositionDepartement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Salles positionnées hors de leur département : une position géocodée y est
 * celle d'un homonyme et s'efface ; une autre origine est signalée.
 * @since 3.0.2
 * @author Kwaite
 */
final class PositionsHorsDepartement {

  private static final Logger LOG = LoggerFactory.getLogger(PositionsHorsDepartement.class);
  private static final String TYPE_SIGNALEMENT = "position_hors_departement";
  private static final String ORIGINE_GEOCODAGE = "geocodage";

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  private record SallePositionnee(
      UUID id, String dept, double latitude, double longitude, String origine) {}

  /* package-private : instancié par ControleDispersionGeo. */
  PositionsHorsDepartement(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * @return nombre de salles signalées
   */
  /* package-private : appelé par ControleDispersionGeo. */
  int verifier() {
    final List<SallePositionnee> horsDepartement =
        jdbc
            .query(
                """
                SELECT id, dept, latitude, longitude, origine_position FROM salles
                WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND dept IS NOT NULL
                """,
                Map.of(),
                (rs, _) ->
                    new SallePositionnee(
                        rs.getObject("id", UUID.class),
                        rs.getString("dept"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getString("origine_position")))
            .stream()
            .filter(s -> !PositionDepartement.plausible(s.dept(), s.latitude(), s.longitude()))
            .toList();
    final Set<UUID> signalees = new HashSet<>();
    int effacees = 0;
    for (final SallePositionnee salle : horsDepartement) {
      if (ORIGINE_GEOCODAGE.equals(salle.origine())) {
        effacerPosition(salle.id());
        effacees++;
      } else {
        signalements.signaler(
            TYPE_SIGNALEMENT,
            "salle",
            salle.id(),
            "moyenne",
            Map.of("dept", salle.dept(), "origine", String.valueOf(salle.origine())));
        signalees.add(salle.id());
      }
    }
    signalements.clore(TYPE_SIGNALEMENT, signalees);
    if (effacees > 0) {
      LOG.info("{} position(s) géocodée(s) hors département effacée(s)", effacees);
    }
    return signalees.size();
  }

  private void effacerPosition(final UUID salleId) {
    jdbc.update(
        "UPDATE salles SET latitude = NULL, longitude = NULL, origine_position = NULL"
            + " WHERE id = CAST(:id AS uuid)",
        Map.of("id", salleId.toString()));
  }
}
