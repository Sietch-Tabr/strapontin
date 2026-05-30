/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.worker.persister;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// Service de recalcul des correspondances utilisateurs (table `user_matches`).
///
/// Pour chaque utilisateur, calcule l'intersection entre ses artistes/salles suivis
/// et les concerts futurs. Met à jour `user_matches` en conséquence.
/// Les concerts indisponibles sont marqués `notifie_le = now()` immédiatement
/// pour ne pas déclencher de badge "Nouveau".
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class RecalculerMatchesService {

  private static final Logger log = LoggerFactory.getLogger(RecalculerMatchesService.class);
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  public RecalculerMatchesService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// @param userIds liste d'IDs à recalculer, ou `null` pour tous les utilisateurs
  @Transactional
  public void recalculerMatches(List<String> userIds) {
    Set<String> usersAvecArtistes =
        new LinkedHashSet<>(
            jdbc.queryForList(
                "SELECT DISTINCT user_id FROM user_artistes", Map.of(), String.class));
    Set<String> usersAvecSalles =
        new LinkedHashSet<>(
            jdbc.queryForList("SELECT DISTINCT user_id FROM user_salles", Map.of(), String.class));

    Set<String> usersSet = new LinkedHashSet<>();
    usersSet.addAll(usersAvecArtistes);
    usersSet.addAll(usersAvecSalles);

    List<String> usersARecalculer =
        userIds != null
            ? userIds.stream().filter(usersSet::contains).toList()
            : new ArrayList<>(usersSet);

    String aujourdhui = LocalDate.now().toString();

    for (String userId : usersARecalculer) {
      recalculerPourUser(userId, aujourdhui);
    }

    purgerMatchesOrphelins(usersSet);
  }

  private void recalculerPourUser(String userId, String aujourdhui) {
    List<String> artistesNorms =
        jdbc.queryForList(
            "SELECT artiste_normalise FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            String.class);

    List<String> salleIds =
        jdbc.queryForList(
            "SELECT regexp_replace(lower(salle_id), '[^a-z0-9]', '', 'g') FROM user_salles WHERE"
                + " user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            String.class);

    if (artistesNorms.isEmpty() && salleIds.isEmpty()) return;

    List<String> departements = obtenirDepartementsUser(userId);
    Set<String> concertIds = new LinkedHashSet<>();

    if (!artistesNorms.isEmpty()) {
      concertIds.addAll(rechercherConcertsParArtistes(artistesNorms, departements, aujourdhui));
    }

    if (!salleIds.isEmpty()) {
      concertIds.addAll(rechercherConcertsParSalles(salleIds, aujourdhui));
    }

    if (concertIds.isEmpty()) {
      jdbc.update("DELETE FROM user_matches WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      return;
    }

    supprimerMatchesPetimes(userId, concertIds);
    insererNouveauxMatches(userId, concertIds);
    marquerIndisponiblesCommeLus(userId, concertIds);
    reinitialiserNotifPreventesOuvertes(userId);

    log.info("matches recalculés userId={} matches={}", userId, concertIds.size());
  }

  private List<String> obtenirDepartementsUser(String userId) {
    try {
      String[] depts =
          jdbc.queryForObject(
              "SELECT departements FROM user_config WHERE user_id = :userId",
              Map.of(CLE_USER_ID, userId),
              (rs, _) -> (String[]) rs.getArray("departements").getArray());
      return List.of(depts);
    } catch (Exception _) {
      return List.of();
    }
  }

  private List<String> rechercherConcertsParArtistes(
      List<String> artistesNorms, List<String> departements, String aujourdhui) {
    Map<String, Object> params = new HashMap<>();
    params.put("artistesNorms", artistesNorms);
    params.put("aujourdhui", LocalDate.parse(aujourdhui));

    // Correspondance exacte OU préfixe espace : "slimane" matche "slimane symphonique"
    // (TM peut renommer l'attraction avec un sous-titre de tournée entre deux fetches).
    StringBuilder matchCond = new StringBuilder("(artiste_norm IN (:artistesNorms)");
    for (int i = 0; i < artistesNorms.size(); i++) {
      String paramPref = "pref" + i;
      params.put(paramPref, artistesNorms.get(i) + " %");
      matchCond.append(" OR artiste_norm LIKE :").append(paramPref);
    }
    matchCond.append(")");

    String sql;
    if (departements.isEmpty()) {
      sql =
          "SELECT DISTINCT id::text FROM concerts WHERE " + matchCond + " AND date >= :aujourdhui";
    } else {
      params.put("departements", departements);
      sql =
          "SELECT DISTINCT id::text FROM concerts WHERE "
              + matchCond
              + " AND date >= :aujourdhui AND dept = ANY(ARRAY[:departements])";
    }
    return jdbc.queryForList(sql, params, String.class);
  }

  private List<String> rechercherConcertsParSalles(List<String> salleIds, String aujourdhui) {
    return jdbc.queryForList(
        "SELECT id::text FROM concerts WHERE regexp_replace(salle_norm, '[^a-z0-9]', '', 'g') IN"
            + " (:salleNorms) AND date >= :aujourdhui",
        Map.of("salleNorms", salleIds, "aujourdhui", LocalDate.parse(aujourdhui)),
        String.class);
  }

  private void supprimerMatchesPetimes(String userId, Set<String> concertIds) {
    jdbc.update(
        "DELETE FROM user_matches WHERE user_id = :userId AND concert_id::text NOT IN"
            + " (:concertIds)",
        Map.of(CLE_USER_ID, userId, "concertIds", concertIds));
  }

  private void insererNouveauxMatches(String userId, Set<String> concertIds) {
    String sql =
        "INSERT INTO user_matches (user_id, concert_id)"
            + " VALUES (:userId, CAST(:concertId AS uuid)) ON CONFLICT DO NOTHING";
    SqlParameterSource[] params =
        concertIds.stream()
            .map(
                id ->
                    new MapSqlParameterSource()
                        .addValue(CLE_USER_ID, userId)
                        .addValue("concertId", id))
            .toArray(SqlParameterSource[]::new);
    if (params.length > 0) {
      jdbc.batchUpdate(sql, params);
    }
  }

  private void marquerIndisponiblesCommeLus(String userId, Set<String> concertIds) {
    jdbc.update(
        """
        UPDATE user_matches um
        SET notification_envoyee_le = now(), vu_le = now()
        WHERE um.user_id = :userId
          AND um.notification_envoyee_le IS NULL
          AND um.concert_id IN (
            SELECT c.id FROM concerts c
            WHERE c.id::text IN (:concertIds)
              AND c.disponible = false
          )
        """,
        Map.of(CLE_USER_ID, userId, "concertIds", concertIds));
  }

  private void reinitialiserNotifPreventesOuvertes(String userId) {
    jdbc.update(
        """
        UPDATE user_matches um
        SET notification_envoyee_le = NULL, statut_notifie = NULL, vu_le = NULL
        FROM concerts c
        WHERE c.id = um.concert_id
          AND um.user_id = :userId
          AND um.statut_notifie = 'presale'
          AND um.masque_le IS NULL
          AND (c.statut IS NULL OR c.statut = 'onsale')
        """,
        Map.of(CLE_USER_ID, userId));
  }

  private void purgerMatchesOrphelins(Set<String> usersAvecAbonnements) {
    if (usersAvecAbonnements.isEmpty()) {
      jdbc.update("DELETE FROM user_matches", Map.of());
      return;
    }
    int supprimes =
        jdbc.update(
            "DELETE FROM user_matches WHERE user_id NOT IN (:userIds)",
            Map.of("userIds", usersAvecAbonnements));
    if (supprimes > 0) {
      log.info("matches orphelins supprimés count={}", supprimes);
    }
  }
}
