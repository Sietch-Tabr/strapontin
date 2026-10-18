/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vue admin des départements sans concert à venir, relevés chaque nuit par le worker
 * ({@code couverture_departements}) : lesquels sont à examiner, lesquels ont une absence reconnue.
 * @since 3.0.6
 * @author Kwaite
 */
@Service
public class CouvertureDepartementsAdminService {

  private static final String CLE_DEPT = "dept";
  private static final String TYPE_SIGNALEMENT = "departement_sans_concert";

  private final NamedParameterJdbcTemplate jdbc;
  private final LogAdminService logAdminService;

  /**
   * Département à zéro concert à venir.
   *
   * @param dept code du département
   * @param sansConcertDepuis premier relevé à zéro, ou null si inconnu
   * @param absenceReconnue {@code true} si l'admin a déclaré cette absence légitime
   */
  public record DepartementSansConcert(
      String dept, LocalDate sansConcertDepuis, boolean absenceReconnue) {}

  /**
   * @param jdbc accès JDBC
   * @param logAdminService journalisation des décisions de l'admin
   */
  public CouvertureDepartementsAdminService(
      final NamedParameterJdbcTemplate jdbc, final LogAdminService logAdminService) {
    this.jdbc = jdbc;
    this.logAdminService = logAdminService;
  }

  /**
   * Départements sans concert à venir, les absences à examiner d'abord.
   *
   * @return la liste, vide si chaque département connu a des concerts
   */
  public List<DepartementSansConcert> lister() {
    return jdbc.query(
        """
        SELECT dept, sans_concert_depuis, couverture_connue_le IS NOT NULL AS reconnue
        FROM couverture_departements WHERE nb_concerts = 0
        ORDER BY reconnue, dept
        """,
        Map.of(),
        (rs, _) ->
            new DepartementSansConcert(
                rs.getString(CLE_DEPT),
                rs.getObject("sans_concert_depuis", LocalDate.class),
                rs.getBoolean("reconnue")));
  }

  /**
   * Déclare légitime l'absence de concert d'un département : plus de signalement tant qu'il reste
   * à zéro.
   *
   * @param dept code du département
   * @return {@code true} si le département est bien sans concert
   */
  @Transactional
  public boolean reconnaitre(final String dept) {
    final int lignes =
        jdbc.update(
            "UPDATE couverture_departements SET couverture_connue_le = now()"
                + " WHERE dept = :dept AND nb_concerts = 0",
            Map.of(CLE_DEPT, dept));
    if (lignes == 0) {
      return false;
    }
    jdbc.update(
        "UPDATE signalements_matching SET resolu_le = now() WHERE type = :type"
            + " AND resolu_le IS NULL AND details ->> 'departement' = :dept",
        Map.of("type", TYPE_SIGNALEMENT, CLE_DEPT, dept));
    logAdminService.logAdmin(
        "info", "admin", "absence de concert reconnue", Map.of("departement", dept));
    return true;
  }

  /**
   * Annule la reconnaissance d'une absence : le département est de nouveau signalé au prochain
   * relevé nocturne.
   *
   * @param dept code du département
   * @return {@code true} si une reconnaissance a été levée
   */
  public boolean rouvrir(final String dept) {
    final int lignes =
        jdbc.update(
            "UPDATE couverture_departements SET couverture_connue_le = NULL"
                + " WHERE dept = :dept AND nb_concerts = 0 AND couverture_connue_le IS NOT NULL",
            Map.of(CLE_DEPT, dept));
    if (lignes > 0) {
      logAdminService.logAdmin(
          "info", "admin", "absence de concert à réexaminer", Map.of("departement", dept));
    }
    return lignes > 0;
  }
}
