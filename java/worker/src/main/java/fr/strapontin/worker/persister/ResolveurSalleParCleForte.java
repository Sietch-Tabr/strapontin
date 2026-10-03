/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Dernier repli par nom : la salle d'un concert déjà en base partageant
 * artiste, date, heure et département, quand deux sources nomment le même
 * lieu sans rapport textuel (« Ed&n » / « Espace Dollfus Noack »).
 * @since 3.0.2
 * @author Kwaite
 */
final class ResolveurSalleParCleForte {

  private static final Logger LOG = LoggerFactory.getLogger(ResolveurSalleParCleForte.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final Function<Set<UUID>, Map<UUID, String>> deptsConnus;

  /* package-private : instancié par ResolveurSalleParConcert. */
  ResolveurSalleParCleForte(
      final NamedParameterJdbcTemplate jdbc,
      final Function<Set<UUID>, Map<UUID, String>> deptsConnus) {
    this.jdbc = jdbc;
    this.deptsConnus = deptsConnus;
  }

  /**
   * Recherche la salle d'un concert existant à même artiste/date/heure/dept
   * — dernier repli quand deux sources désignent le même évènement sous des
   * noms sans rapport (ex. Eventim "Ed&n"/TM "Espace Dollfus Noack").
   * @param concert concert dont la salle reste à résoudre
   * @return UUID de la salle du concert existant correspondant, ou {@code null}
   */
  /* package-private : appelé par ResolveurSalleParConcert. */
  UUID chercher(final ValeurConcert concert) {
    if (concert.heure() == null || concert.dept() == null || concert.artisteNorm() == null) {
      return null;
    }
    final List<UUID> candidats =
        jdbc.query(
            "SELECT DISTINCT salle_id FROM concerts"
                + " WHERE artiste_norm = :artisteNorm AND date = :date AND heure = :heure"
                + " AND dept = :dept AND salle_id IS NOT NULL"
                + " ORDER BY salle_id"
                + " LIMIT 20",
            Map.of(
                "artisteNorm",
                concert.artisteNorm(),
                "date",
                concert.date(),
                "heure",
                concert.heure(),
                "dept",
                concert.dept()),
            (rs, _) -> rs.getObject("salle_id", UUID.class));
    if (candidats.isEmpty()) {
      return null;
    }

    final UUID salleId = premierCandidatGeographiquementCoherent(candidats, concert.dept());
    if (salleId == null) {
      LOG.warn(
          "clé forte (artiste+date+heure+dept) sans candidat géographiquement cohérent"
              + " artiste={} date={} candidats={}",
          concert.artiste(),
          concert.date(),
          candidats);
      return null;
    }

    LOG.info(
        "salle rattachée par clé forte (artiste+date+heure+dept) artiste={} date={} salle={}"
            + " uuid={}",
        concert.artiste(),
        concert.date(),
        concert.salle(),
        salleId);
    return salleId;
  }

  /**
   * Retient, parmi les candidats de la clé forte, le premier dont le
   * département connu ne contredit pas le concert entrant (évite qu'un
   * concert mal rattaché reconfirme son propre mauvais rattachement).
   * @param candidats identifiants candidats, dans un ordre déterministe
   * @param deptEntrant département du concert à résoudre
   * @return premier candidat géographiquement cohérent, ou {@code null}
   */
  private UUID premierCandidatGeographiquementCoherent(
      final List<UUID> candidats, final String deptEntrant) {
    final Map<UUID, String> depts = deptsConnus.apply(new HashSet<>(candidats));
    for (final UUID candidat : candidats) {
      final String deptConnu = depts.get(candidat);
      if (deptConnu == null || deptConnu.equals(deptEntrant)) {
        return candidat;
      }
    }
    return null;
  }
}
