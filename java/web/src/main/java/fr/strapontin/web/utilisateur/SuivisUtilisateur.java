/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.utilisateur;

import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Définition unique du décompte des artistes et des salles qu'un utilisateur suit : si la notion
 * de « suivi » change (statut, filtre), elle ne change qu'ici.
 * @since 3.1.4
 * @author Kwaite
 */
public final class SuivisUtilisateur {

  private static final String CLE_USER_ID = "userId";
  private static final String SQL_ARTISTES =
      "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId";
  private static final String SQL_SALLES =
      "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId";

  private SuivisUtilisateur() {}

  /**
   * @param jdbc accès JDBC
   * @param userId identifiant de l'utilisateur
   * @return nombre d'artistes suivis
   */
  public static int nombreArtistes(final NamedParameterJdbcTemplate jdbc, final String userId) {
    return compter(jdbc, SQL_ARTISTES, userId);
  }

  /**
   * @param jdbc accès JDBC
   * @param userId identifiant de l'utilisateur
   * @return nombre de salles suivies
   */
  public static int nombreSalles(final NamedParameterJdbcTemplate jdbc, final String userId) {
    return compter(jdbc, SQL_SALLES, userId);
  }

  /**
   * @param jdbc accès JDBC
   * @param userId identifiant de l'utilisateur
   * @return nombre d'artistes et de salles suivis, ensemble
   */
  public static int nombreTotal(final NamedParameterJdbcTemplate jdbc, final String userId) {
    return nombreArtistes(jdbc, userId) + nombreSalles(jdbc, userId);
  }

  private static int compter(
      final NamedParameterJdbcTemplate jdbc, final String sql, final String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(sql, Map.of(CLE_USER_ID, userId), Integer.class), 0);
  }
}
