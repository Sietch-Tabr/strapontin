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
/// Pour chaque utilisateur, calcule l'intersection entre ses artistes/salles suivis et les concerts
/// futurs. Met à jour `user_matches` en conséquence. Les concerts indisponibles sont marqués
/// `notification_envoyee_le = now()` immédiatement pour ne pas déclencher de badge "Nouveau".
///
/// <p>Les recalculs par utilisateur sont parallélisés via des threads virtuels, chacun dans sa
/// propre transaction ({@link TransactionTemplate}).
///
/// @since 2.0.0
/// @author Kwaite
@Service
public class RecalculerMatchesService {

  private static final Logger log = LoggerFactory.getLogger(RecalculerMatchesService.class);
  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate txParUser;

  /// @param jdbc accès JDBC
  /// @param txManager gestionnaire de transactions (transaction par utilisateur)
  public RecalculerMatchesService(
      NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.txParUser = new TransactionTemplate(txManager);
  }

  /// Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
  ///
  /// <p>Les recalculs sont exécutés en parallèle via des threads virtuels. Chaque utilisateur est
  /// traité dans sa propre transaction : un échec isolé n'interrompt pas les autres recalculs.
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

  private void recalculerPourUser(String userId, String aujourdhui) {
    List<UUID> artisteIds =
        jdbc.queryForList(
            "SELECT artiste_id FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            UUID.class);

    List<UUID> salleIds =
        jdbc.queryForList(
            "SELECT salle_id FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            UUID.class);

    if (artisteIds.isEmpty() && salleIds.isEmpty()) return;

    List<String> departements = obtenirDepartementsUser(userId);
    Set<UUID> concertIds = new LinkedHashSet<>();

    if (!artisteIds.isEmpty()) {
      concertIds.addAll(rechercherConcertsParArtistes(artisteIds, departements, aujourdhui));
    }

    if (!salleIds.isEmpty()) {
      concertIds.addAll(rechercherConcertsParSalles(salleIds, aujourdhui));
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

  /// Exclut une fiche package Ticketmaster (voir {@code PersisteurConcertsDb.SQL_EST_PACKAGE`) tant
  /// qu'aucune fiche normale du même événement réel (même artiste/salle/date) n'existe — elle
  /// redevient visible dès qu'une fiche normale apparaît et que {@code supprimerDoublonsParSalleId}
  /// les fusionne.
  private static final String SQL_PAS_PACKAGE_ISOLE =
      " AND (c.url IS NULL OR c.url !~* '/pack/' OR EXISTS ("
          + "   SELECT 1 FROM concerts c2"
          + "   WHERE c2.artiste_id = c.artiste_id AND c2.salle_id = c.salle_id"
          + "     AND c2.date = c.date AND c2.id != c.id"
          + "     AND (c2.url IS NULL OR c2.url !~* '/pack/')"
          + " ))";

  private List<UUID> rechercherConcertsParArtistes(
      List<UUID> artisteIds, List<String> departements, String aujourdhui) {
    Map<String, Object> params = new HashMap<>();
    params.put("artisteIds", artisteIds);
    params.put("aujourdhui", LocalDate.parse(aujourdhui));

    String sql;
    if (departements.isEmpty()) {
      sql =
          "SELECT DISTINCT c.id FROM concerts c"
              + " WHERE c.artiste_id IN (:artisteIds) AND c.date >= :aujourdhui"
              + SQL_PAS_PACKAGE_ISOLE;
    } else {
      params.put("departements", departements);
      sql =
          "SELECT DISTINCT c.id FROM concerts c"
              + " WHERE c.artiste_id IN (:artisteIds) AND c.date >= :aujourdhui"
              + " AND c.dept = ANY(ARRAY[:departements])"
              + SQL_PAS_PACKAGE_ISOLE;
    }
    return jdbc.queryForList(sql, params, UUID.class);
  }

  private List<UUID> rechercherConcertsParSalles(List<UUID> salleIds, String aujourdhui) {
    return jdbc.queryForList(
        "SELECT DISTINCT c.id FROM concerts c"
            + " WHERE c.salle_id IN (:salleIds) AND c.date >= :aujourdhui"
            + SQL_PAS_PACKAGE_ISOLE,
        Map.of("salleIds", salleIds, "aujourdhui", LocalDate.parse(aujourdhui)),
        UUID.class);
  }

  private void supprimerMatchesPetimes(String userId, Set<UUID> concertIds) {
    jdbc.update(
        "DELETE FROM user_matches WHERE user_id = :userId AND concert_id NOT IN (:concertIds)",
        Map.of(CLE_USER_ID, userId, "concertIds", concertIds));
  }

  private void insererNouveauxMatches(String userId, Set<UUID> concertIds) {
    String sql =
        "INSERT INTO user_matches (user_id, concert_id)"
            + " VALUES (:userId, :concertId) ON CONFLICT DO NOTHING";
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

  private void marquerIndisponiblesCommeLus(String userId, Set<UUID> concertIds) {
    jdbc.update(
        """
        UPDATE user_matches um
        SET notification_envoyee_le = now(), vu_le = now()
        WHERE um.user_id = :userId
          AND um.notification_envoyee_le IS NULL
          AND um.concert_id IN (
            SELECT c.id FROM concerts c
            WHERE c.id IN (:concertIds)
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
