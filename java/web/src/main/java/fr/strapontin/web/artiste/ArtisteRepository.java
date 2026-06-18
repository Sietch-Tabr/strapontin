/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/// Accès aux données pour les artistes suivis ({@code user_artistes}).
///
/// <p>SQL pur — aucune logique métier. Toutes les décisions (cap, unicité) appartiennent à
/// {@link ArtisteService}.
///
/// @since 2.1.0
/// @author Kwaite
@Repository
class ArtisteRepository {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;

  ArtisteRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Retourne les artistes suivis par l'utilisateur, triés par nom.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return liste des artistes suivis
  List<ArtisteUtilisateur> listerPourUtilisateur(String userId) {
    return jdbc.query(
        """
        SELECT artiste_normalise, artiste_affiche, created_at
        FROM user_artistes
        WHERE user_id = :userId
        ORDER BY lower(artiste_affiche)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new ArtisteUtilisateur(
                rs.getString("artiste_normalise"),
                rs.getString("artiste_affiche"),
                rs.getTimestamp("created_at").toInstant()));
  }

  /// Recherche des artistes dans les concerts existants par pattern normalisé.
  ///
  /// @param pattern valeur LIKE (ex. {@code "%pattern%"})
  /// @return noms d'artistes distincts (max 5)
  List<String> rechercherEnBase(String pattern) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT artiste FROM concerts
        WHERE artiste_norm LIKE :pattern
        ORDER BY artiste
        LIMIT 5
        """,
        Map.of("pattern", pattern),
        String.class);
  }

  /// Vérifie si un artiste normalisé est déjà suivi par l'utilisateur.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param normalise nom normalisé de l'artiste
  /// @return {@code true} si déjà présent
  boolean existePourUtilisateur(String userId, String normalise) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_artistes
            WHERE user_id = :userId AND artiste_normalise = :normalise
            """,
            Map.of(CLE_USER_ID, userId, "normalise", normalise),
            Integer.class)
        .isEmpty();
  }

  /// Compte les artistes suivis par l'utilisateur.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return nombre d'artistes suivis
  int compterPourUtilisateur(String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }

  /// Insère ou récupère un artiste dans le référentiel global et retourne son UUID stable.
  ///
  /// @param nom      nom affiché
  /// @param nomNorm  nom normalisé (clé unique)
  /// @return UUID stable de l'artiste (existant ou nouvellement créé)
  UUID upsertArtiste(String nom, String nomNorm) {
    return jdbc.queryForObject(
        """
        INSERT INTO artistes (nom, nom_norm)
        VALUES (:nom, :nomNorm)
        ON CONFLICT (nom_norm) DO UPDATE SET nom = EXCLUDED.nom
        RETURNING id
        """,
        Map.of("nom", nom, "nomNorm", nomNorm),
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  /// Insère un artiste dans la liste de suivi.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param normalise nom normalisé
  /// @param nom       nom affiché
  /// @param artisteId UUID stable de l'artiste dans le référentiel global
  void ajouter(String userId, String normalise, String nom, UUID artisteId) {
    Map<String, Object> params = new java.util.HashMap<>();
    params.put(CLE_USER_ID, userId);
    params.put("normalise", normalise);
    params.put("nom", nom);
    params.put("artisteId", artisteId != null ? artisteId.toString() : null);
    jdbc.update(
        """
        INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche, artiste_id)
        VALUES (:userId, :normalise, :nom, CAST(:artisteId AS uuid))
        """,
        params);
  }

  /// Supprime un artiste de la liste de suivi.
  ///
  /// @param userId            identifiant de l'utilisateur
  /// @param artisteNormalise  nom normalisé de l'artiste à supprimer
  void supprimer(String userId, String artisteNormalise) {
    jdbc.update(
        """
        DELETE FROM user_artistes
        WHERE user_id = :userId AND artiste_normalise = :artisteNormalise
        """,
        Map.of(CLE_USER_ID, userId, "artisteNormalise", artisteNormalise));
  }
}
