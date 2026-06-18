/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.persister;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/// Service de recalcul des correspondances utilisateurs (table `user_matches`).
///
/// Pour chaque utilisateur, calcule l'intersection entre ses artistes/salles suivis
/// et les concerts futurs. Met à jour `user_matches` en conséquence.
/// Les concerts indisponibles sont marqués `notification_envoyee_le = now()` immédiatement
/// pour ne pas déclencher de badge "Nouveau".
///
/// <p>Les recalculs par utilisateur sont parallélisés via des threads virtuels,
/// chacun dans sa propre transaction ({@link TransactionTemplate}).
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class RecalculerMatchesService {

  private static final Logger log = LoggerFactory.getLogger(RecalculerMatchesService.class);
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate txParUser;

  /// @param jdbc      accès JDBC
  /// @param txManager gestionnaire de transactions (transaction par utilisateur)
  public RecalculerMatchesService(
      NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.txParUser = new TransactionTemplate(txManager);
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// <p>Les recalculs sont exécutés en parallèle via des threads virtuels.
  /// Chaque utilisateur est traité dans sa propre transaction : un échec isolé
  /// n'interrompt pas les autres recalculs.
  ///
  /// @param userIds liste d'IDs à recalculer, ou {@code null} pour tous les utilisateurs
  /// @return nombre d'utilisateurs traités
  public int recalculerMatches(List<String> userIds) {
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

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          usersARecalculer.stream()
              .map(
                  userId ->
                      CompletableFuture.runAsync(
                          () -> {
                            try {
                              txParUser.executeWithoutResult(
                                  _ -> recalculerPourUser(userId, aujourdhui));
                            } catch (Exception e) {
                              log.error(
                                  "erreur recalcul userId={} : {}", userId, e.getMessage(), e);
                            }
                          },
                          executor))
              .toList();
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    purgerMatchesOrphelins(usersSet);
    return usersARecalculer.size();
  }

  private record SalleRef(String cle, UUID uuid) {}

  private record ArtisteRef(String norm, UUID id) {}

  private void recalculerPourUser(String userId, String aujourdhui) {
    List<ArtisteRef> artisteRefs =
        jdbc.query(
            "SELECT artiste_normalise, artiste_id FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            (rs, _) ->
                new ArtisteRef(
                    rs.getString("artiste_normalise"), rs.getObject("artiste_id", UUID.class)));

    List<SalleRef> salleRefs =
        jdbc.query(
            "SELECT regexp_replace(lower(salle_id), '[^a-z0-9]', '', 'g') AS cle, salle_uuid"
                + " FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            (rs, _) -> new SalleRef(rs.getString("cle"), rs.getObject("salle_uuid", UUID.class)));

    if (artisteRefs.isEmpty() && salleRefs.isEmpty()) return;

    List<String> artistesNorms = artisteRefs.stream().map(ArtisteRef::norm).toList();
    List<UUID> artisteIds =
        artisteRefs.stream().map(ArtisteRef::id).filter(id -> id != null).toList();
    List<String> salleIds = salleRefs.stream().map(SalleRef::cle).toList();
    List<UUID> salleUuids = salleRefs.stream().map(SalleRef::uuid).filter(u -> u != null).toList();

    List<String> departements = obtenirDepartementsUser(userId);
    Set<String> concertIds = new LinkedHashSet<>();

    if (!artistesNorms.isEmpty()) {
      concertIds.addAll(
          rechercherConcertsParArtistes(artistesNorms, artisteIds, departements, aujourdhui));
    }

    if (!salleRefs.isEmpty()) {
      concertIds.addAll(rechercherConcertsParSalles(salleIds, salleUuids, aujourdhui));
    }

    if (concertIds.isEmpty()) {
      jdbc.update("DELETE FROM user_matches WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      log.info("matches supprimés userId={} aucun concert correspondant", userId);
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
      List<String> artistesNorms,
      List<UUID> artisteIds,
      List<String> departements,
      String aujourdhui) {
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
    if (!artisteIds.isEmpty()) {
      List<String> artisteIdsStr = artisteIds.stream().map(UUID::toString).toList();
      params.put("artisteIds", artisteIdsStr);
      matchCond.append(" OR artiste_id::text IN (:artisteIds)");
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

  private List<String> rechercherConcertsParSalles(
      List<String> salleIds, List<UUID> salleUuids, String aujourdhui) {
    Map<String, Object> params = new HashMap<>();
    params.put("aujourdhui", LocalDate.parse(aujourdhui));

    StringBuilder cond = new StringBuilder();
    if (!salleUuids.isEmpty()) {
      List<String> uuidsStr = salleUuids.stream().map(UUID::toString).toList();
      params.put("salleUuids", uuidsStr);
      cond.append("salle_uuid::text IN (:salleUuids)");
    }
    if (!salleIds.isEmpty()) {
      params.put("salleNorms", salleIds);
      if (!cond.isEmpty()) cond.append(" OR ");
      cond.append("salle_cle IN (:salleNorms)");
    }
    if (cond.isEmpty()) return List.of();

    return jdbc.queryForList(
        "SELECT DISTINCT id::text FROM concerts WHERE (" + cond + ") AND date >= :aujourdhui",
        params,
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
