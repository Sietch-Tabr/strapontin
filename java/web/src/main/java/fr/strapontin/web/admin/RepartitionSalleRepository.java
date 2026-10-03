/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.admin;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès SQL de la répartition d'une salle ({@link RepartitionSalleService}).
 *
 * @since 3.0.2
 * @author Kwaite
 */
@Repository
class RepartitionSalleRepository {

  private static final String CLE_SALLE = "salle";
  private static final String CLE_CIBLE = "cible";
  private static final String CLE_LIBELLES = "libelles";
  private static final String CLE_IDS = "ids";

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : réservé à RepartitionSalleService. */
  RepartitionSalleRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /* package-private : réservé à RepartitionSalleService. */
  List<RepartitionSalleService.LibelleSource> listerLibelles(final UUID salleId) {
    return jdbc.query(
        """
        SELECT o.salle_brut AS libelle, count(DISTINCT e.id) AS nb
        FROM evenements e JOIN observations o ON o.evenement_id = e.id
        WHERE e.salle_id = CAST(:salle AS uuid)
        GROUP BY o.salle_brut
        ORDER BY nb DESC, libelle
        """,
        Map.of(CLE_SALLE, salleId.toString()),
        (rs, _) ->
            new RepartitionSalleService.LibelleSource(rs.getString("libelle"), rs.getLong("nb")));
  }

  /* package-private : réservé à RepartitionSalleService. */
  Optional<Map<String, Object>> lireSalle(final UUID salleId) {
    return jdbc
        .queryForList(
            "SELECT id, nom, dept FROM salles WHERE id = CAST(:salle AS uuid)",
            Map.of(CLE_SALLE, salleId.toString()))
        .stream()
        .findFirst();
  }

  /* package-private : réservé à RepartitionSalleService. */
  UUID creerSalle(final String nom, final String nomNorm, final String dept) {
    final boolean cleLibre =
        jdbc.queryForList(
                "SELECT 1 FROM salles WHERE salle_cle = :cle",
                Map.of("cle", nomNorm),
                Integer.class)
            .isEmpty();
    final String cle = cleLibre ? nomNorm : nomNorm + " " + UUID.randomUUID();
    return jdbc.queryForObject(
        "INSERT INTO salles (salle_cle, nom, nom_norm, dept) VALUES (:cle, :nom, :nomNorm, :dept)"
            + " RETURNING id",
        new MapSqlParameterSource()
            .addValue("cle", cle)
            .addValue("nom", nom)
            .addValue("nomNorm", nomNorm)
            .addValue("dept", dept, Types.VARCHAR),
        UUID.class);
  }

  /** Événements de la salle dont toutes les observations portent l'un des libellés. */
  /* package-private : réservé à RepartitionSalleService. */
  List<UUID> evenementsEntierementLibelles(final UUID salleId, final List<String> libelles) {
    return jdbc.queryForList(
        """
        SELECT e.id FROM evenements e
        WHERE e.salle_id = CAST(:salle AS uuid)
          AND EXISTS (SELECT 1 FROM observations o
                      WHERE o.evenement_id = e.id AND o.salle_brut IN (:libelles))
          AND NOT EXISTS (SELECT 1 FROM observations o
                          WHERE o.evenement_id = e.id AND o.salle_brut NOT IN (:libelles))
        """,
        Map.of(CLE_SALLE, salleId.toString(), CLE_LIBELLES, libelles),
        UUID.class);
  }

  /** Événements dont une partie seulement des observations porte ces libellés. */
  /* package-private : réservé à RepartitionSalleService. */
  int compterEvenementsMixtes(final UUID salleId, final List<String> libelles) {
    final Integer nb =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM evenements e
            WHERE e.salle_id = CAST(:salle AS uuid)
              AND EXISTS (SELECT 1 FROM observations o
                          WHERE o.evenement_id = e.id AND o.salle_brut IN (:libelles))
              AND EXISTS (SELECT 1 FROM observations o
                          WHERE o.evenement_id = e.id AND o.salle_brut NOT IN (:libelles))
            """,
            Map.of(CLE_SALLE, salleId.toString(), CLE_LIBELLES, libelles),
            Integer.class);
    return nb != null ? nb : 0;
  }

  /**
   * Fusionne dans la cible les événements qu'elle a déjà (artiste, date,
   * heure) : observations et correspondances reportées, source supprimée.
   * @return nombre d'événements fusionnés
   */
  /* package-private : réservé à RepartitionSalleService. */
  int fusionnerEvenementsEnDoublon(final List<UUID> evenements, final UUID cibleId) {
    final MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue(CLE_IDS, evenements)
            .addValue(CLE_CIBLE, cibleId.toString());
    final String doublons =
        """
        SELECT e1.id AS source_id, e2.id AS cible_id
        FROM evenements e1 JOIN evenements e2
          ON e2.salle_id = CAST(:cible AS uuid) AND e2.artiste_norm = e1.artiste_norm
         AND e2.date = e1.date AND e2.heure IS NOT DISTINCT FROM e1.heure
        WHERE e1.id IN (:ids)
        """;
    jdbc.update(
        "DELETE FROM observations o USING ("
            + doublons
            + ") d, observations o2 WHERE o.evenement_id = d.source_id"
            + " AND o2.evenement_id = d.cible_id AND o2.source = o.source"
            + " AND o2.url IS NOT DISTINCT FROM o.url",
        params);
    jdbc.update(
        "UPDATE observations o SET evenement_id = d.cible_id FROM ("
            + doublons
            + ") d WHERE o.evenement_id = d.source_id",
        params);
    jdbc.update(
        "DELETE FROM user_matches um USING ("
            + doublons
            + ") d, user_matches um2 WHERE um.concert_id = d.source_id"
            + " AND um2.concert_id = d.cible_id AND um2.user_id = um.user_id",
        params);
    jdbc.update(
        "UPDATE user_matches um SET concert_id = d.cible_id FROM ("
            + doublons
            + ") d WHERE um.concert_id = d.source_id",
        params);
    return jdbc.update(
        "DELETE FROM evenements e WHERE e.id IN (:ids)"
            + " AND NOT EXISTS (SELECT 1 FROM observations o WHERE o.evenement_id = e.id)",
        params);
  }

  /* package-private : réservé à RepartitionSalleService. */
  int deplacerEvenements(final List<UUID> evenements, final UUID cibleId) {
    return jdbc.update(
        "UPDATE evenements SET salle_id = CAST(:cible AS uuid) WHERE id IN (:ids)",
        new MapSqlParameterSource()
            .addValue(CLE_IDS, evenements)
            .addValue(CLE_CIBLE, cibleId.toString()));
  }

  /* package-private : réservé à RepartitionSalleService. */
  List<Map<String, Object>> mappingsEventim(final UUID salleId) {
    return jdbc.queryForList(
        "SELECT identifiant_externe, libelle_source FROM salle_sources"
            + " WHERE salle_id = CAST(:salle AS uuid) AND source = 'eventim'",
        Map.of(CLE_SALLE, salleId.toString()));
  }

  /* package-private : réservé à RepartitionSalleService. */
  void deplacerMapping(final String identifiant, final UUID cibleId) {
    jdbc.update(
        "UPDATE salle_sources SET salle_id = CAST(:cible AS uuid)"
            + " WHERE source = 'eventim' AND identifiant_externe = :identifiant",
        Map.of(CLE_CIBLE, cibleId.toString(), "identifiant", identifiant));
  }

  // Position du premier lieu déplacé, pour une salle qui n'en a pas.
  /* package-private : réservé à RepartitionSalleService. */
  void completerPositionDepuisMappings(final UUID cibleId) {
    jdbc.update(
        """
        UPDATE salles s SET latitude = ss.latitude, longitude = ss.longitude,
                            origine_position = 'sources',
                            code_postal = COALESCE(s.code_postal, ss.code_postal)
        FROM (SELECT latitude, longitude, code_postal FROM salle_sources
              WHERE salle_id = CAST(:cible AS uuid) AND latitude IS NOT NULL LIMIT 1) ss
        WHERE s.id = CAST(:cible AS uuid) AND s.latitude IS NULL
        """,
        Map.of(CLE_CIBLE, cibleId.toString()));
  }

  /* package-private : réservé à RepartitionSalleService. */
  void leverContamination(final UUID salleId) {
    jdbc.update(
        "UPDATE salles SET contamination_signalee = NULL WHERE id = CAST(:salle AS uuid)",
        Map.of(CLE_SALLE, salleId.toString()));
  }
}
