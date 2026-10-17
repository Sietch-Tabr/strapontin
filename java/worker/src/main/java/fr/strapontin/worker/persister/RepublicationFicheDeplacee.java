/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.CorrespondanceSalle;
import fr.strapontin.core.Normalisation;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reconnaît la republication, par une billetterie, de l'ancienne fiche d'un concert déplacé : sa
 * fiche (même source, même adresse) est déjà rattachée à l'événement d'une autre salle, tout en
 * ayant été vue sous le nom de l'ancienne. L'accepter recréerait chaque nuit un événement fantôme
 * dans l'ancienne salle, et défairait toute fusion manuelle.
 * @since 3.0.6
 * @author Kwaite
 */
final class RepublicationFicheDeplacee {

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par PersisteurConcertsDb (même package). */
  RepublicationFicheDeplacee(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param concert concert entrant, salle déjà résolue
   * @return {@code true} si sa fiche est déjà celle d'un événement d'une autre salle, vue sous le
   *     nom de la salle du concert entrant
   */
  /* package-private : appelé par PersisteurConcertsDb (même package). */
  boolean estRepublication(final ValeurConcert concert) {
    if (concert.url() == null || concert.salleId() == null) {
      return false;
    }
    final List<String> nomsVus =
        jdbc.queryForList(
            """
            SELECT o.salle_brut
            FROM observations o JOIN evenements e ON e.id = o.evenement_id
            WHERE o.source = CAST(:source AS source_concert) AND o.url = :url
              AND e.date = :date AND e.salle_id IS NOT NULL
              AND e.salle_id <> CAST(:salleId AS uuid)
            """,
            Map.of(
                "source",
                concert.source(),
                "url",
                concert.url(),
                "date",
                concert.date(),
                "salleId",
                concert.salleId().toString()),
            String.class);
    return nomsVus.stream()
        .map(Normalisation::normaliserTexte)
        .anyMatch(
            vu ->
                vu.equals(concert.salleNorm())
                    || CorrespondanceSalle.estDoublonSalle(vu, concert.salleNorm()));
  }
}
