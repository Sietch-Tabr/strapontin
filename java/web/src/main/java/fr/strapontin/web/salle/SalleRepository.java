/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
        SELECT us.salle_uuid AS id, s.nom, us.created_at
        FROM user_salles us
        JOIN salles s ON s.id = us.salle_uuid
        WHERE us.user_id = :userId
        ORDER BY lower(s.nom)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new SalleUtilisateur(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getTimestamp("created_at").toInstant()));
  }

  /// Recherche des salles dans les concerts existants par pattern sur la clé normalisée.
  ///
  /// <p>Utilise {@code regexp_replace(salle_norm, '[^a-z0-9]', '', 'g') LIKE :pattern}
  /// pour ignorer apostrophes, tirets et accents dans le terme de recherche.
  ///
  /// @param pattern valeur LIKE sur la clé normalisée (ex. {@code "%lempreinte%"})
  /// @return lignes {@code salle}/{@code ville} distinctes (max 10)
  List<Map<String, Object>> rechercherEnBase(String pattern) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT salle, ville FROM concerts
        WHERE regexp_replace(salle_norm, '[^a-z0-9]', '', 'g') LIKE :pattern
        ORDER BY salle
        LIMIT 10
        """,
        Map.of("pattern", pattern));
  }

  /// Vérifie si une salle est déjà suivie par l'utilisateur.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleUuid UUID stable de la salle
  /// @return {@code true} si déjà présente
  boolean existePourUtilisateur(String userId, UUID salleUuid) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_salles
            WHERE user_id = :userId AND salle_uuid = CAST(:salleUuid AS uuid)
            """,
            Map.of(CLE_USER_ID, userId, "salleUuid", salleUuid.toString()),
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

  /// Insère ou met à jour une salle dans le référentiel et retourne son UUID stable.
  ///
  /// @param salleCle clé naturelle normalisée (ex. {@code "olympia"})
  /// @param nom      nom affiché
  /// @return UUID stable de la salle (existant ou nouvellement créé)
  UUID upsertSalle(String salleCle, String nom) {
    Map<String, Object> params = new HashMap<>();
    params.put("salleCle", salleCle);
    params.put("nom", nom);
    params.put("nomNorm", salleCle);
    return jdbc.queryForObject(
        """
        INSERT INTO salles (salle_cle, nom, nom_norm)
        VALUES (:salleCle, :nom, :nomNorm)
        ON CONFLICT (salle_cle) DO UPDATE SET nom = EXCLUDED.nom
        RETURNING id
        """,
        params,
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  /// Enregistre une correspondance source externe → salle interne.
  ///
  /// @param salleUuid           UUID stable de la salle
  /// @param source              nom de la source (ex. {@code "ticketmaster"})
  /// @param identifiantExterne  identifiant dans la source externe
  void enregistrerSalleSource(UUID salleUuid, String source, String identifiantExterne) {
    jdbc.update(
        """
        INSERT INTO salle_sources (salle_id, source, identifiant_externe)
        VALUES (CAST(:salleUuid AS uuid), :source, :identifiantExterne)
        ON CONFLICT DO NOTHING
        """,
        Map.of(
            "salleUuid", salleUuid.toString(),
            "source", source,
            "identifiantExterne", identifiantExterne));
  }

  /// Lie une salle à un utilisateur.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleUuid UUID stable de la salle
  void ajouterPourUtilisateur(String userId, UUID salleUuid) {
    jdbc.update(
        """
        INSERT INTO user_salles (user_id, salle_uuid)
        VALUES (:userId, CAST(:salleUuid AS uuid))
        """,
        Map.of(CLE_USER_ID, userId, "salleUuid", salleUuid.toString()));
  }

  /// Supprime la relation utilisateur–salle.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleUuid UUID stable de la salle
  void supprimerPourUtilisateur(String userId, UUID salleUuid) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE user_id = :userId AND salle_uuid = CAST(:salleUuid AS uuid)
        """,
        Map.of(CLE_USER_ID, userId, "salleUuid", salleUuid.toString()));
  }
}
