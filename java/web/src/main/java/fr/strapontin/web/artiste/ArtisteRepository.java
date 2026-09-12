/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.artiste;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès aux données pour les artistes suivis ({@code user_artistes}).
 *
 * <p>SQL pur — aucune logique métier. Toutes les décisions (cap, unicité) appartiennent à {@link
 * ArtisteService}.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Repository
class ArtisteRepository {

  private static final String CLE_USER_ID = "userId";
  private static final String CLE_ARTISTE_ID = "artisteId";

  private final NamedParameterJdbcTemplate jdbc;

  ArtisteRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Retourne les artistes suivis par l'utilisateur, triés par nom.
   *
   * @param userId identifiant de l'utilisateur
   * @return liste des artistes suivis
   */
  List<ArtisteUtilisateur> listerPourUtilisateur(final String userId) {
    return jdbc.query(
        """
        SELECT artiste_id AS id, artiste_affiche, created_at
        FROM user_artistes
        WHERE user_id = :userId
        ORDER BY lower(artiste_affiche)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new ArtisteUtilisateur(
                rs.getObject("id", UUID.class),
                rs.getString("artiste_affiche"),
                rs.getTimestamp("created_at").toInstant()));
  }

  /**
   * Recherche des artistes dans les concerts existants par pattern normalisé.
   *
   * @param pattern valeur LIKE (ex. {@code "%pattern%"})
   * @return noms d'artistes distincts (max 5)
   */
  List<String> rechercherEnBase(final String pattern) {
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

  /**
   * Vérifie si un artiste est déjà suivi par l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @param artisteId UUID stable de l'artiste
   * @return {@code true} si déjà présent
   */
  boolean existePourUtilisateur(final String userId, final UUID artisteId) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_artistes
            WHERE user_id = :userId AND artiste_id = CAST(:artisteId AS uuid)
            """,
            Map.of(CLE_USER_ID, userId, CLE_ARTISTE_ID, artisteId.toString()),
            Integer.class)
        .isEmpty();
  }

  /**
   * Compte les artistes suivis par l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return nombre d'artistes suivis
   */
  int compterPourUtilisateur(final String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }

  /**
   * Recherche les artistes du référentiel dont le {@code nom_norm} partage au moins un des tokens
   * fournis — pré-filtre SQL avant application de la correspondance floue ({@code
   * CorrespondanceArtiste.estDoublonArtiste}, non exprimable en SQL).
   *
   * @param tokens tokens significatifs (≥3 caractères) du nom recherché
   * @return jusqu'à 50 candidats {@code (id, nom_norm)}, vide si {@code tokens} est vide
   */
  List<Map<String, Object>> chercherCandidatsParTokens(final Set<String> tokens) {
    if (tokens.isEmpty()) {
      return List.of();
    }

    final List<String> tokensList = new ArrayList<>(tokens);
    final StringBuilder whereTokens = new StringBuilder("(");
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) {
        whereTokens.append(" OR ");
      }
      whereTokens.append("nom_norm LIKE :t").append(i);
    }
    whereTokens.append(")");

    final MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }

    return jdbc.queryForList(
        "SELECT id, nom_norm FROM artistes WHERE " + whereTokens + " LIMIT 50", params);
  }

  /**
   * Insère ou récupère un artiste dans le référentiel global et retourne son UUID stable.
   *
   * @param nom nom affiché
   * @param nomNorm nom normalisé (clé unique)
   * @return UUID stable de l'artiste (existant ou nouvellement créé)
   */
  UUID upsertArtiste(final String nom, final String nomNorm) {
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

  /**
   * Insère un artiste dans la liste de suivi.
   *
   * @param userId identifiant de l'utilisateur
   * @param nom nom affiché
   * @param artisteId UUID stable de l'artiste dans le référentiel global
   */
  void ajouter(final String userId, final String nom, final UUID artisteId) {
    jdbc.update(
        """
        INSERT INTO user_artistes (user_id, artiste_affiche, artiste_id)
        VALUES (:userId, :nom, CAST(:artisteId AS uuid))
        """,
        Map.of(CLE_USER_ID, userId, "nom", nom, CLE_ARTISTE_ID, artisteId.toString()));
  }

  /**
   * Enregistre une correspondance source externe → artiste interne.
   *
   * @param artisteId UUID stable de l'artiste
   * @param source nom de la source (ex. {@code "wikidata"}, {@code "deezer"})
   * @param identifiantExterne identifiant dans la source externe
   */
  void enregistrerArtisteSource(
      final UUID artisteId, final String source, final String identifiantExterne) {
    jdbc.update(
        """
        INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)
        VALUES (CAST(:artisteId AS uuid), :source, :identifiantExterne)
        ON CONFLICT DO NOTHING
        """,
        Map.of(
            CLE_ARTISTE_ID,
            artisteId.toString(),
            "source",
            source,
            "identifiantExterne",
            identifiantExterne));
  }

  /**
   * Met à jour {@code wikidata_id}/{@code deezer_id} d'un artiste si absents — ne touche jamais une
   * valeur déjà résolue par le job nocturne ({@code COALESCE}). Peut lever {@link
   * org.springframework.dao.DuplicateKeyException} ({@code idx_artistes_wikidata_id}) — à la charge
   * de l'appelant (même piège qu'{@code EnrichissementArtistesService}, jalon 2.6.0).
   *
   * @param artisteId UUID stable de l'artiste
   * @param wikidataId Q-ID Wikidata (peut être {@code null})
   * @param deezerId identifiant Deezer (peut être {@code null})
   */
  void mettreAJourIdsWikidata(
      final UUID artisteId, final String wikidataId, final String deezerId) {
    final Map<String, Object> params = new HashMap<>();
    params.put("id", artisteId.toString());
    params.put("wikidataId", wikidataId);
    params.put("deezerId", deezerId);
    jdbc.update(
        """
        UPDATE artistes SET
          wikidata_id = COALESCE(wikidata_id, :wikidataId),
          deezer_id   = COALESCE(deezer_id, :deezerId)
        WHERE id = CAST(:id AS uuid)
        """,
        params);
  }

  /**
   * Supprime un artiste de la liste de suivi.
   *
   * @param userId identifiant de l'utilisateur
   * @param artisteId UUID stable de l'artiste à supprimer
   * @return le nombre de lignes réellement supprimées (0 si l'artiste n'était pas suivi)
   */
  int supprimer(final String userId, final UUID artisteId) {
    return jdbc.update(
        """
        DELETE FROM user_artistes
        WHERE user_id = :userId AND artiste_id = CAST(:artisteId AS uuid)
        """,
        Map.of(CLE_USER_ID, userId, CLE_ARTISTE_ID, artisteId.toString()));
  }
}
