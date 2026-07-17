/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.sources.TicketmasterSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
  /// <p>Le champ {@code aDigitick} est {@code true} si la salle possède un slug Digitick
  /// configuré dans {@code salle_sources}.
  ///
  /// @param userId identifiant de l'utilisateur
  /// @return liste des salles suivies
  List<SalleUtilisateur> listerPourUtilisateur(String userId) {
    return jdbc.query(
        """
        SELECT us.salle_id AS id, s.nom, us.created_at,
               EXISTS (
                   SELECT 1 FROM salle_sources ss
                   WHERE ss.salle_id = s.id AND ss.source = 'digitick'
               ) AS a_digitick
        FROM user_salles us
        JOIN salles s ON s.id = us.salle_id
        WHERE us.user_id = :userId
        ORDER BY lower(s.nom)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new SalleUtilisateur(
                rs.getObject("id", UUID.class),
                rs.getString("nom"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("a_digitick")));
  }

  /// Recherche des salles dans les concerts existants, le référentiel Digitick et
  /// les salles connues avec concerts.
  ///
  /// <p>Union de trois sources :
  /// <ol>
  ///   <li>Concerts en base sans UUID — {@code salle_norm} nettoyé LIKE :pattern</li>
  ///   <li>Salles configurées Digitick — {@code salles.nom_norm} nettoyé LIKE :pattern</li>
  ///   <li>Salles connues avec concerts ({@code salle_id IS NOT NULL}) — évite un appel TM
  ///       inutile et permet à {@code estMemeSalle} de filtrer les doublons de venueId</li>
  /// </ol>
  ///
  /// @param pattern valeur LIKE sur la clé normalisée (ex. {@code "%lempreinte%"})
  /// @return lignes {@code salle}/{@code ville}/{@code digitick_slug} distinctes (max 10)
  List<Map<String, Object>> rechercherEnBase(String pattern) {
    return jdbc.queryForList(
        """
        SELECT salle, ville, digitick_slug FROM (
            SELECT DISTINCT salle, ville, null::text AS digitick_slug
            FROM concerts
            WHERE regexp_replace(salle_norm, '[^a-z0-9]', '', 'g') LIKE :pattern
              AND salle_id IS NULL
            UNION
            SELECT DISTINCT s.nom AS salle, s.ville AS ville, ss.identifiant_externe AS digitick_slug
            FROM salles s
            JOIN salle_sources ss ON ss.salle_id = s.id AND ss.source = 'digitick'
            WHERE regexp_replace(s.nom_norm, '[^a-z0-9]', '', 'g') LIKE :pattern
            UNION
            SELECT DISTINCT s.nom AS salle, s.ville AS ville, null::text AS digitick_slug
            FROM salles s
            WHERE EXISTS (SELECT 1 FROM concerts c WHERE c.salle_id = s.id)
              AND regexp_replace(s.nom_norm, '[^a-z0-9]', '', 'g') LIKE :pattern
        ) t
        ORDER BY salle
        LIMIT 10
        """,
        Map.of("pattern", pattern));
  }

  /// Vérifie si une salle est déjà suivie par l'utilisateur.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleId UUID stable de la salle
  /// @return {@code true} si déjà présente
  boolean existePourUtilisateur(String userId, UUID salleId) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_salles
            WHERE user_id = :userId AND salle_id = CAST(:salleId AS uuid)
            """,
            Map.of(CLE_USER_ID, userId, "salleId", salleId.toString()),
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

  /// Recherche les salles du référentiel dont la {@code salle_cle} partage au moins un des
  /// tokens fournis — pré-filtre SQL avant application de la correspondance floue
  /// ({@code CorrespondanceSalle.estDoublonSalle} sur {@code nom}, non exprimable en SQL).
  ///
  /// @param tokens clés normalisées ({@code SalleService.normaliserSalleCle}) des mots du nom
  ///     recherché, filtrées aux tokens d'au moins 3 caractères
  /// @return jusqu'à 50 candidats {@code (id, salle_cle, nom)}, vide si {@code tokens} est vide
  List<Map<String, Object>> chercherCandidatsParCle(Set<String> tokens) {
    if (tokens.isEmpty()) return List.of();

    List<String> tokensList = new ArrayList<>(tokens);
    StringBuilder whereTokens = new StringBuilder("(");
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) whereTokens.append(" OR ");
      whereTokens.append("salle_cle LIKE :t").append(i);
    }
    whereTokens.append(")");

    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }

    return jdbc.queryForList(
        "SELECT id, salle_cle, nom FROM salles WHERE " + whereTokens + " LIMIT 50", params);
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

  /// Insère ou met à jour une salle avec ville et code postal (usage admin Digitick).
  ///
  /// <p>En cas de conflit sur {@code salle_cle}, met à jour le nom, la ville et le CP si fournis.
  ///
  /// @param salleCle clé naturelle normalisée
  /// @param nom      nom affiché
  /// @param ville    ville (null si inconnue)
  /// @param dept     numéro de département (null si inconnu)
  /// @return UUID stable de la salle
  UUID upsertSalleAvecLoc(String salleCle, String nom, String ville, String dept) {
    Map<String, Object> params = new HashMap<>();
    params.put("salleCle", salleCle);
    params.put("nom", nom);
    params.put("nomNorm", salleCle);
    params.put("ville", ville);
    params.put("dept", dept);
    return jdbc.queryForObject(
        """
        INSERT INTO salles (salle_cle, nom, nom_norm, ville, dept)
        VALUES (:salleCle, :nom, :nomNorm, :ville, :dept)
        ON CONFLICT (salle_cle) DO UPDATE
            SET nom   = EXCLUDED.nom,
                ville = COALESCE(EXCLUDED.ville, salles.ville),
                dept  = COALESCE(EXCLUDED.dept, salles.dept)
        RETURNING id
        """,
        params,
        (rs, _) -> rs.getObject("id", UUID.class));
  }

  /// Met à jour le nom, la ville et le CP d'une salle existante.
  ///
  /// @param salleId UUID de la salle
  /// @param nom       nouveau nom (null = inchangé)
  /// @param ville     nouvelle ville (null = inchangée)
  /// @param dept      nouveau numéro de département (null = inchangé)
  void mettreAJourSalle(UUID salleId, String nom, String ville, String dept) {
    jdbc.update(
        """
        UPDATE salles
        SET nom   = COALESCE(:nom, nom),
            ville = COALESCE(:ville, ville),
            dept  = COALESCE(:dept, dept)
        WHERE id = CAST(:uuid AS uuid)
        """,
        Map.of(
            "uuid", salleId.toString(),
            "nom", (Object) nom,
            "ville", (Object) ville,
            "dept", (Object) dept));
  }

  /// Retourne les identifiants préfixés de toutes les sources d'une salle, séparés par une virgule.
  ///
  /// <p>Ex. : {@code "digitick:lempreinte"} ou {@code "tm:rZFSnyZdFvA,digitick:lempreinte"}. Le
  /// préfixe Ticketmaster est abrégé ({@code "tm"}, pas {@code "ticketmaster"}, la valeur
  /// canonique de {@code salle_sources.source}) — {@link
  // TicketmasterSource#accepteIdentifiantSalle}
  /// n'accepte que ce préfixe court (#966), sans quoi le fetch déclenché à l'ajout d'une salle
  /// TM ne fait jamais rien (mismatch silencieux, aucune exception).
  ///
  /// @param salleId UUID de la salle
  /// @return CSV d'identifiants ou {@code null} si aucune source connue
  String lireIdentifiantsSources(UUID salleId) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT source, identifiant_externe
            FROM salle_sources
            WHERE salle_id = CAST(:uuid AS uuid)
            ORDER BY source
            """,
            Map.of("uuid", salleId.toString()));
    if (lignes.isEmpty()) return null;
    List<String> ids = new ArrayList<>();
    for (Map<String, Object> l : lignes) {
      String source = (String) l.get("source");
      String prefixe =
          "ticketmaster".equals(source) ? TicketmasterSource.PREFIXE_IDENTIFIANT : source;
      ids.add(prefixe + ":" + l.get("identifiant_externe"));
    }
    return String.join(",", ids);
  }

  /// Supprime une salle uniquement si aucun utilisateur ne la suit et si elle n'a pas de concerts.
  ///
  /// @param salleId UUID de la salle
  /// @return {@code true} si supprimée, {@code false} si des contraintes empêchent la suppression
  boolean supprimerSalleDigitick(UUID salleId) {
    int nb =
        jdbc.update(
            """
            DELETE FROM salles
            WHERE id = CAST(:uuid AS uuid)
              AND NOT EXISTS (
                  SELECT 1 FROM user_salles WHERE salle_id = CAST(:uuid AS uuid))
              AND NOT EXISTS (
                  SELECT 1 FROM concerts WHERE salle_id = CAST(:uuid AS uuid))
            """,
            Map.of("uuid", salleId.toString()));
    return nb > 0;
  }

  /// Met à jour {@code wikidata_id} d'une salle si absent (#718) — ne touche jamais une valeur
  /// déjà résolue par le job nocturne ({@code COALESCE}). Peut lever
  /// {@link org.springframework.dao.DuplicateKeyException} ({@code idx_salles_wikidata_id}) — à
  /// la charge de l'appelant (même piège qu'{@code EnrichissementSallesService}, jalon 2.6.0).
  ///
  /// @param salleId  UUID stable de la salle
  /// @param wikidataId Q-ID Wikidata
  void mettreAJourWikidataId(UUID salleId, String wikidataId) {
    jdbc.update(
        """
        UPDATE salles SET wikidata_id = COALESCE(wikidata_id, :wikidataId)
        WHERE id = CAST(:uuid AS uuid)
        """,
        Map.of("uuid", salleId.toString(), "wikidataId", wikidataId));
  }

  /// Recherche une salle différente de {@code exclureSalleId} déjà associée à un slug Digitick
  /// (#830) — permet de détecter un conflit avant l'attribution plutôt que d'échouer
  /// silencieusement sur {@code ON CONFLICT DO NOTHING}.
  ///
  /// @param slug             slug Digitick à vérifier
  /// @param exclureSalleId salle en cours d'attribution, exclue de la recherche
  /// @return le nom affiché de la salle en conflit, si elle existe
  Optional<String> trouverConflitDigitickSlug(String slug, UUID exclureSalleId) {
    List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT COALESCE(s.nom_affiche, s.nom) AS nom
            FROM salle_sources ss
            JOIN salles s ON s.id = ss.salle_id
            WHERE ss.source = 'digitick' AND ss.identifiant_externe = :slug
              AND ss.salle_id != CAST(:uuid AS uuid)
            """,
            Map.of("slug", slug, "uuid", exclureSalleId.toString()));
    return lignes.isEmpty() ? Optional.empty() : Optional.of((String) lignes.get(0).get("nom"));
  }

  /// Enregistre une correspondance source externe → salle interne.
  ///
  /// @param salleId           UUID stable de la salle
  /// @param source              nom de la source (ex. {@code "ticketmaster"})
  /// @param identifiantExterne  identifiant dans la source externe
  void enregistrerSalleSource(UUID salleId, String source, String identifiantExterne) {
    jdbc.update(
        """
        INSERT INTO salle_sources (salle_id, source, identifiant_externe)
        VALUES (CAST(:salleId AS uuid), :source, :identifiantExterne)
        ON CONFLICT DO NOTHING
        """,
        Map.of(
            "salleId", salleId.toString(),
            "source", source,
            "identifiantExterne", identifiantExterne));
  }

  /// Lie une salle à un utilisateur.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleId UUID stable de la salle
  void ajouterPourUtilisateur(String userId, UUID salleId) {
    jdbc.update(
        """
        INSERT INTO user_salles (user_id, salle_id)
        VALUES (:userId, CAST(:salleId AS uuid))
        """,
        Map.of(CLE_USER_ID, userId, "salleId", salleId.toString()));
  }

  /// Supprime la relation utilisateur–salle.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param salleId UUID stable de la salle
  void supprimerPourUtilisateur(String userId, UUID salleId) {
    jdbc.update(
        """
        DELETE FROM user_salles
        WHERE user_id = :userId AND salle_id = CAST(:salleId AS uuid)
        """,
        Map.of(CLE_USER_ID, userId, "salleId", salleId.toString()));
  }
}
