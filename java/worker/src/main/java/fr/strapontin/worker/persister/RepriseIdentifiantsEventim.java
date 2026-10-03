/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import fr.strapontin.core.IdentifiantLieuEventim;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reprise d'un lieu Eventim qualifié par code postal, encore inconnu, par la
 * salle de son identifiant historique (nom seul) du même département : les
 * homonymes d'autres départements, qui partageaient cet identifiant, restent
 * écartés.
 * @since 3.0.2
 * @author Kwaite
 */
final class RepriseIdentifiantsEventim {

  private static final String PREFIXE_EVENTIM = "eventim:";

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié par PersisteurConcertsDb. */
  RepriseIdentifiantsEventim(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param concerts concerts du lot
   * @param cachesSalles identifiant source préfixé → salle, complété en place
   */
  /* package-private : appelé par PersisteurConcertsDb. */
  void reprendre(final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Map<String, ValeurConcert> qualifiesInconnus = qualifiesInconnus(concerts, cachesSalles);
    if (qualifiesInconnus.isEmpty()) {
      return;
    }
    final Map<String, Map<String, Object>> parHistorique = chargerHistoriques(qualifiesInconnus);
    for (final Map.Entry<String, ValeurConcert> entree : qualifiesInconnus.entrySet()) {
      final String qualifie = entree.getKey().substring(PREFIXE_EVENTIM.length());
      final Map<String, Object> historique =
          parHistorique.get(IdentifiantLieuEventim.historique(qualifie));
      if (historique != null && memeDepartement(historique, entree.getValue())) {
        final UUID salleId = (UUID) historique.get("salle_id");
        jdbc.update(
            """
            INSERT INTO salle_sources (salle_id, source, identifiant_externe)
            VALUES (CAST(:salleId AS uuid), 'eventim', :identifiant)
            ON CONFLICT (source, identifiant_externe) DO NOTHING
            """,
            Map.of("salleId", salleId.toString(), "identifiant", qualifie));
        cachesSalles.put(entree.getKey(), salleId);
      }
    }
  }

  private static Map<String, ValeurConcert> qualifiesInconnus(
      final List<ValeurConcert> concerts, final Map<String, UUID> cachesSalles) {
    final Map<String, ValeurConcert> resultat = new HashMap<>();
    for (final ValeurConcert concert : concerts) {
      final String id = concert.salleSourceId();
      if (id != null
          && id.startsWith(PREFIXE_EVENTIM)
          && !cachesSalles.containsKey(id)
          && !IdentifiantLieuEventim.historique(id).equals(id)) {
        resultat.putIfAbsent(id, concert);
      }
    }
    return resultat;
  }

  private Map<String, Map<String, Object>> chargerHistoriques(
      final Map<String, ValeurConcert> qualifiesInconnus) {
    final List<String> historiques =
        qualifiesInconnus.keySet().stream()
            .map(id -> IdentifiantLieuEventim.historique(id.substring(PREFIXE_EVENTIM.length())))
            .distinct()
            .toList();
    final Map<String, Map<String, Object>> parHistorique = new HashMap<>();
    for (final Map<String, Object> ligne :
        jdbc.queryForList(
            """
            SELECT ss.identifiant_externe AS identifiant, ss.salle_id AS salle_id, s.dept AS dept
            FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'eventim' AND ss.identifiant_externe IN (:ids)
            """,
            Map.of("ids", historiques))) {
      parHistorique.put((String) ligne.get("identifiant"), ligne);
    }
    return parHistorique;
  }

  private static boolean memeDepartement(
      final Map<String, Object> historique, final ValeurConcert entrant) {
    final String deptSalle = (String) historique.get("dept");
    return deptSalle == null || entrant.dept() == null || deptSalle.equals(entrant.dept());
  }
}
