/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/// Accès aux données pour les salles suivies ({@code salles}, {@code user_salles}).
///
/// <p>SQL pur — aucune logique métier. Toutes les décisions (cap, unicité, normalisation)
/// appartiennent à {@link SalleService}.
///
/// @since 2.1.0
/// @author Kwaite
@Repository
class SalleRepository {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  SalleRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Retourne les salles suivies par l'utilisateur, triées par nom.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return liste des salles suivies
  List<SalleUtilisateur> listerPourUtilisateur(String userId) {
    return jdbc.query(
        """
        SELECT us.salle_id, s.nom, us.tm_venue_id, us.created_at
        FROM user_salles us
        JOIN salles s ON s.id = us.salle_id
        WHERE us.user_id = :userId
        ORDER BY lower(s.nom)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new SalleUtilisateur(
                rs.getString("salle_id"),
                rs.getString("nom"),
                rs.getString("tm_venue_id"),
                rs.getTimestamp("created_at").toInstant()));
  }

  /// Recherche des salles dans les concerts existants par pattern ILIKE.
  ///
  /// @param pattern valeur ILIKE (ex. {@code "%opéra%"})
  /// @return lignes {@code salle}/{@code ville} distinctes (max 10)
  List<Map<String, Object>> rechercherEnBase(String pattern) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT salle, ville FROM concerts
        WHERE salle ILIKE :pattern
        ORDER BY salle
        LIMIT 10
        """,
        Map.of("pattern", pattern));
  }

  /// Vérifie si une salle est déjà suivie par l'utilisateur.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param salleId identifiant normalisé de la salle
  /// @return {@code true} si déjà présente
  boolean existePourUtilisateur(String userId, String salleId) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_salles
            WHERE user_id = :userId AND salle_id = :salleId
            """,
            Map.of(CLE_USER_ID, userId, "salleId", salleId),
            Integer.class)
        .isEmpty();
  }

  /// Compte les salles suivies par l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return nombre de salles suivies
  int compterPourUtilisateur(String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }

  /// Insère ou met à jour une salle dans le référentiel.
  ///
  /// @param salleId identifiant normalisé (clé naturelle)
  /// @param nom     nom affiché
  void upsertSalle(String salleId, String nom) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", salleId);
    params.put("nom", nom);
    params.put("nomNorm", salleId);
    jdbc.update(
        """
        INSERT INTO salles (id, nom, nom_norm)
        VALUES (:id, :nom, :nomNorm)
        ON CONFLICT (id) DO UPDATE SET nom = EXCLUDED.nom
        """,
        params);
  }

  /// Lie une salle à un utilisateur.
  ///
  /// @param userId     identifiant de l'utilisateur
  /// @param salleId    identifiant normalisé de la salle
  /// @param tmVenueId  identifiant Ticketmaster (nullable)
  void ajouterPourUtilisateur(String userId, String salleId, String tmVenueId) {
    Map<String, Object> params = new HashMap<>();
    params.put(CLE_USER_ID, userId);
    params.put("salleId", salleId);
    params.put("tmVenueId", tmVenueId);
    jdbc.update(
        """
        INSERT INTO user_salles (user_id, salle_id, tm_venue_id)
        VALUES (:userId, :salleId, :tmVenueId)
        """,
        params);
  }

  /// Supprime la relation utilisateur–salle.
  ///
  /// @param userId  identifiant de l'utilisateur
  /// @param salleId identifiant normalisé de la salle
  void supprimerPourUtilisateur(String userId, String salleId) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE user_id = :userId AND salle_id = :salleId
        """,
        Map.of(CLE_USER_ID, userId, "salleId", salleId));
  }
}
