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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service de recalcul des correspondances utilisateurs (table {@code user_matches}).
 *
 * <p>Pour chaque utilisateur, calcule l'intersection entre ses artistes/salles suivis et les
 * concerts futurs. Met à jour {@code user_matches} en conséquence. Les concerts indisponibles sont
 * marqués {@code notification_envoyee_le = now()} immédiatement pour ne pas déclencher de badge
 * "Nouveau".
 *
 * <p>Les recalculs par utilisateur sont parallélisés via des threads virtuels, chacun dans sa
 * propre transaction ({@link TransactionTemplate}).
 *
 * @since 2.0.0
 * @author Kwaite
 */
@Service
public class RecalculerMatchesService {

  private static final Logger LOG = LoggerFactory.getLogger(RecalculerMatchesService.class);
  private static final String CLE_USER_ID = "userId";

  /**
   * Exclut une fiche package Ticketmaster (voir {@code PersisteurConcertsDb.SQL_EST_PACKAGE`) tant
   * qu'aucune fiche normale du même événement réel (même artiste/salle/date) n'existe — elle
   * redevient visible dès qu'une fiche normale apparaît et que {@code supprimerDoublonsParSalleId}
   * les fusionne.
   */
  private static final String SQL_PAS_PACKAGE_ISOLE =
      " AND (c.url IS NULL OR c.url !~* '/pack/' OR EXISTS ("
          + "   SELECT 1 FROM concerts c2"
          + "   WHERE c2.artiste_id = c.artiste_id AND c2.salle_id = c.salle_id"
          + "     AND c2.date = c.date AND c2.id != c.id"
          + "     AND (c2.url IS NULL OR c2.url !~* '/pack/')"
          + " ))";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate txParUser;

  /**
   * @param jdbc accès JDBC
   * @param txManager gestionnaire de transactions (transaction par utilisateur)
   */
  public RecalculerMatchesService(
      final NamedParameterJdbcTemplate jdbc, final PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.txParUser = new TransactionTemplate(txManager);
  }

  /**
   * Recalcule les matches pour tous les utilisateurs ou un sous-ensemble.
   *
   * <p>Les recalculs sont exécutés en parallèle via des threads virtuels. Chaque utilisateur est
   * traité dans sa propre transaction : un échec isolé n'interrompt pas les autres recalculs.
   *
   * @param userIds liste d'IDs à recalculer, ou {@code null} pour tous les utilisateurs
   * @return nombre d'utilisateurs traités
   */
  public int recalculerMatches(final List<String> userIds) {
    final Set<String> usersAvecArtistes =
        new LinkedHashSet<>(
            jdbc.queryForList(
                "SELECT DISTINCT user_id FROM user_artistes", Map.of(), String.class));
    final Set<String> usersAvecSalles =
        new LinkedHashSet<>(
            jdbc.queryForList("SELECT DISTINCT user_id FROM user_salles", Map.of(), String.class));

    final Set<String> usersSet = new LinkedHashSet<>();
    usersSet.addAll(usersAvecArtistes);
    usersSet.addAll(usersAvecSalles);

    final List<String> usersARecalculer =
        userIds != null
            ? userIds.stream().filter(usersSet::contains).toList()
            : new ArrayList<>(usersSet);

    final String aujourdhui = LocalDate.now().toString();

    // Executors.newVirtualThreadPerTaskExecutor est la primitive Java 21+ recommandée pour un
    // parallélisme borné par utilisateur — pas un contexte de requête J2EE, la justification de la
    // règle ne s'applique pas ici.
    @SuppressWarnings("PMD.DoNotUseThreads")
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try (executor) {
      final List<CompletableFuture<Void>> futures =
          usersARecalculer.stream()
              .map(
                  userId ->
                      CompletableFuture.runAsync(
                          () -> {
                            try {
                              txParUser.executeWithoutResult(
                                  _ -> recalculerPourUser(userId, aujourdhui));
                            } catch (Exception e) {
                              LOG.error(
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

  private void recalculerPourUser(final String userId, final String aujourdhui) {
    final List<UUID> artisteIds =
        jdbc.queryForList(
            "SELECT artiste_id FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            UUID.class);

    final List<UUID> salleIds =
        jdbc.queryForList(
            "SELECT salle_id FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            UUID.class);

    if (artisteIds.isEmpty() && salleIds.isEmpty()) {
      return;
    }

    final List<String> departements = obtenirDepartementsUser(userId);
    final Set<UUID> concertIds = new LinkedHashSet<>();

    if (!artisteIds.isEmpty()) {
      concertIds.addAll(rechercherConcertsParArtistes(artisteIds, departements, aujourdhui));
    }

    if (!salleIds.isEmpty()) {
      concertIds.addAll(rechercherConcertsParSalles(salleIds, aujourdhui));
    }

    if (concertIds.isEmpty()) {
      jdbc.update("DELETE FROM user_matches WHERE user_id = :userId", Map.of(CLE_USER_ID, userId));
      LOG.info("matches supprimés userId={} aucun concert correspondant", userId);
      return;
    }

    supprimerMatchesPetimes(userId, concertIds);
    insererNouveauxMatches(userId, concertIds);
    marquerIndisponiblesCommeLus(userId, concertIds);
    reinitialiserNotifPreventesOuvertes(userId);

    LOG.info("matches recalculés userId={} matches={}", userId, concertIds.size());
  }

  private List<String> obtenirDepartementsUser(final String userId) {
    try {
      final String[] depts =
          jdbc.queryForObject(
              "SELECT departements FROM user_config WHERE user_id = :userId",
              Map.of(CLE_USER_ID, userId),
              (rs, _) -> (String[]) rs.getArray("departements").getArray());
      return List.of(depts);
    } catch (Exception _) {
      return List.of();
    }
  }

  private List<UUID> rechercherConcertsParArtistes(
      final List<UUID> artisteIds, final List<String> departements, final String aujourdhui) {
    final Map<String, Object> params = new HashMap<>();
    params.put("artisteIds", artisteIds);
    params.put("aujourdhui", LocalDate.parse(aujourdhui));

    final String sql;
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

  // Suivre le complexe (salle parent) doit aussi remonter les concerts de ses sous-salles — un nom
  // sans qualificatif (voir ResolveurSalleParConcert) résout précisément vers ce parent, "site,
  // salle non précisée". Suivre une sous-salle précise reste inchangé (pas de remontée aux frères).
  private List<UUID> rechercherConcertsParSalles(
      final List<UUID> salleIds, final String aujourdhui) {
    return jdbc.queryForList(
        "SELECT DISTINCT c.id FROM concerts c"
            + " WHERE (c.salle_id IN (:salleIds)"
            + "        OR c.salle_id IN (SELECT id FROM salles WHERE salle_parent_id IN"
            + " (:salleIds)))"
            + " AND c.date >= :aujourdhui"
            + SQL_PAS_PACKAGE_ISOLE,
        Map.of("salleIds", salleIds, "aujourdhui", LocalDate.parse(aujourdhui)),
        UUID.class);
  }

  private void supprimerMatchesPetimes(final String userId, final Set<UUID> concertIds) {
    jdbc.update(
        "DELETE FROM user_matches WHERE user_id = :userId AND concert_id NOT IN (:concertIds)",
        Map.of(CLE_USER_ID, userId, "concertIds", concertIds));
  }

  private void insererNouveauxMatches(final String userId, final Set<UUID> concertIds) {
    final String sql =
        "INSERT INTO user_matches (user_id, concert_id)"
            + " VALUES (:userId, :concertId) ON CONFLICT DO NOTHING";
    final SqlParameterSource[] params =
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

  private void marquerIndisponiblesCommeLus(final String userId, final Set<UUID> concertIds) {
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

  private void reinitialiserNotifPreventesOuvertes(final String userId) {
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

  private void purgerMatchesOrphelins(final Set<String> usersAvecAbonnements) {
    if (usersAvecAbonnements.isEmpty()) {
      jdbc.update("DELETE FROM user_matches", Map.of());
      return;
    }
    final int supprimes =
        jdbc.update(
            "DELETE FROM user_matches WHERE user_id NOT IN (:userIds)",
            Map.of("userIds", usersAvecAbonnements));
    if (supprimes > 0) {
      LOG.info("matches orphelins supprimés count={}", supprimes);
    }
  }
}
