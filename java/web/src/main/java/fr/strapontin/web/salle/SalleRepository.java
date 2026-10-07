/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.salle;

import fr.strapontin.core.Normalisation;
import fr.strapontin.sources.TicketmasterSource;
import java.sql.Types;
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
 * Accès aux données pour les salles suivies ({@code salles}, {@code
 * user_salles}) — SQL pur, aucune logique métier (cap/unicité/
 * normalisation dans {@link SalleService}).
 * @since 2.1.0
 * @author Kwaite
 */
@Repository
class SalleRepository {

  private static final String CLE_USER_ID = "userId";
  private static final String CLE_NOM = "nom";
  private static final String CLE_SALLE_ID = "salleId";
  private static final String CLE_UUID = "uuid";

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  SalleRepository(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Retourne les salles suivies par l'utilisateur, triées par nom.
   * @param userId identifiant de l'utilisateur
   * @return liste des salles suivies
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  List<SalleUtilisateur> listerPourUtilisateur(final String userId) {
    return jdbc.query(
        """
        SELECT us.salle_id AS id, s.nom, us.created_at
        FROM user_salles us
        JOIN salles s ON s.id = us.salle_id
        WHERE us.user_id = :userId
        ORDER BY lower(s.nom)
        """,
        Map.of(CLE_USER_ID, userId),
        (rs, _) ->
            new SalleUtilisateur(
                rs.getObject("id", UUID.class),
                rs.getString(CLE_NOM),
                rs.getTimestamp("created_at").toInstant()));
  }

  /**
   * Recherche des salles dans les concerts existants et les salles connues
   * avec concerts — union de deux sources (concert sans UUID, salle connue
   * avec concert), pour filtrer les doublons de venueId côté appelant
   * ({@code estMemeSalle}).
   * @param pattern valeur LIKE sur la clé normalisée
   * @return lignes salle/ville distinctes (max 10)
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  List<Map<String, Object>> rechercherEnBase(final String pattern) {
    return jdbc.queryForList(
        """
        SELECT salle, ville FROM (
            SELECT DISTINCT salle, ville
            FROM concerts
            WHERE salle_cle LIKE :pattern
              AND salle_id IS NULL
            UNION
            SELECT DISTINCT s.nom AS salle, s.ville AS ville
            FROM salles s
            WHERE EXISTS (SELECT 1 FROM concerts c WHERE c.salle_id = s.id)
              AND s.nom_cle LIKE :pattern
        ) t
        ORDER BY salle
        LIMIT 10
        """,
        Map.of("pattern", pattern));
  }

  /**
   * Vérifie si une salle est déjà suivie par l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @param salleId UUID stable de la salle
   * @return {@code true} si déjà présente
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  boolean existePourUtilisateur(final String userId, final UUID salleId) {
    return !jdbc.queryForList(
            """
            SELECT 1 FROM user_salles
            WHERE user_id = :userId AND salle_id = CAST(:salleId AS uuid)
            """,
            Map.of(CLE_USER_ID, userId, CLE_SALLE_ID, salleId.toString()),
            Integer.class)
        .isEmpty();
  }

  /**
   * Compte les salles suivies par l'utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @return nombre de salles suivies
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  int compterPourUtilisateur(final String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }

  /**
   * Recherche les salles du référentiel dont la {@code salle_cle} partage
   * au moins un des tokens fournis — pré-filtre SQL avant la correspondance
   * floue ({@code CorrespondanceSalle.estDoublonSalle}, non exprimable en
   * SQL).
   * @param tokens clés normalisées, filtrées aux tokens ≥ 3 caractères
   * @return jusqu'à 50 candidats (id, salle_cle, nom), vide si tokens vide
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  List<Map<String, Object>> chercherCandidatsParCle(final Set<String> tokens) {
    if (tokens.isEmpty()) {
      return List.of();
    }

    final List<String> tokensList = new ArrayList<>(tokens);
    final StringBuilder whereTokens = new StringBuilder(50).append('(');
    for (int i = 0; i < tokensList.size(); i++) {
      if (i > 0) {
        whereTokens.append(" OR ");
      }
      whereTokens.append("salle_cle LIKE :t").append(i);
    }
    whereTokens.append(')');

    final MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < tokensList.size(); i++) {
      params.addValue("t" + i, "%" + tokensList.get(i) + "%");
    }

    return jdbc.queryForList(
        "SELECT id, salle_cle, nom FROM salles WHERE " + whereTokens + " LIMIT 50", params);
  }

  /**
   * Insère ou met à jour une salle dans le référentiel et retourne son UUID
   * stable.
   *
   * @param salleCle clé naturelle normalisée (ex. {@code "olympia"} )
   * @param nom nom affiché
   * @return UUID stable de la salle (existant ou nouvellement créé)
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  UUID upsertSalle(final String salleCle, final String nom) {
    final Map<String, Object> params = new HashMap<>();
    params.put("salleCle", salleCle);
    params.put(CLE_NOM, nom);
    params.put("nomNorm", Normalisation.normaliserTexte(nom));
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

  /**
   * Identifiants préfixés CSV de toutes les sources d'une salle (ex. {@code
   * "tm:rZFSnyZdFvA,eventim:internal:accor arena"}) — préfixe TM abrégé ({@code
   * "tm"}, pas {@code "ticketmaster"}), seul accepté par {@link
   * TicketmasterSource#accepteIdentifiantSalle} (sinon fetch TM muet).
   * @param salleId UUID de la salle
   * @return CSV d'identifiants ou null si aucune source connue
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  String lireIdentifiantsSources(final UUID salleId) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(
            """
            SELECT source, identifiant_externe
            FROM salle_sources
            WHERE salle_id = CAST(:uuid AS uuid)
            ORDER BY source
            """,
            Map.of(CLE_UUID, salleId.toString()));
    if (lignes.isEmpty()) {
      return null;
    }
    final List<String> ids = new ArrayList<>();
    for (final Map<String, Object> l : lignes) {
      final String source = (String) l.get("source");
      final String prefixe =
          "ticketmaster".equals(source) ? TicketmasterSource.PREFIXE_IDENTIFIANT : source;
      ids.add(prefixe + ":" + l.get("identifiant_externe"));
    }
    return String.join(",", ids);
  }

  /**
   * Salle déjà rattachée au lieu Ticketmaster ou à l'élément Wikidata
   * proposés par la recherche, avant toute résolution par le nom.
   * @param tmVenueId identifiant Ticketmaster, peut être {@code null}
   * @param wikidataId Q-ID Wikidata, peut être {@code null}
   * @return la salle trouvée, ou {@code null}
   */
  /* package-private : accès SQL réservé à SalleService (même package). */
  UUID trouverParIdentifiants(final String tmVenueId, final String wikidataId) {
    final List<UUID> salles =
        jdbc.queryForList(
            """
            SELECT salle_id FROM salle_sources
            WHERE source = 'ticketmaster' AND identifiant_externe = :tm
            UNION ALL
            SELECT id FROM salles WHERE wikidata_id = :qid
            LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("tm", tmVenueId, Types.VARCHAR)
                .addValue("qid", wikidataId, Types.VARCHAR),
            UUID.class);
    return salles.isEmpty() ? null : salles.get(0);
  }

  /**
   * Met à jour {@code wikidata_id} si absent ({@code COALESCE}, jamais
   * une valeur déjà résolue) — peut lever {@link
   * org.springframework.dao.DuplicateKeyException}, à la charge de
   * l'appelant.
   * @param salleId UUID stable de la salle
   * @param wikidataId Q-ID Wikidata
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  void mettreAJourWikidataId(final UUID salleId, final String wikidataId) {
    jdbc.update(
        """
        UPDATE salles SET wikidata_id = COALESCE(wikidata_id, :wikidataId)
        WHERE id = CAST(:uuid AS uuid)
        """,
        Map.of(CLE_UUID, salleId.toString(), "wikidataId", wikidataId));
  }

  /**
   * Enregistre une correspondance source externe → salle interne.
   *
   * @param salleId UUID stable de la salle
   * @param source nom de la source (ex. {@code "ticketmaster"})
   * @param identifiantExterne identifiant dans la source externe
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  void enregistrerSalleSource(
      final UUID salleId, final String source, final String identifiantExterne) {
    jdbc.update(
        """
        INSERT INTO salle_sources (salle_id, source, identifiant_externe)
        VALUES (CAST(:salleId AS uuid), :source, :identifiantExterne)
        ON CONFLICT DO NOTHING
        """,
        Map.of(
            CLE_SALLE_ID,
            salleId.toString(),
            "source",
            source,
            "identifiantExterne",
            identifiantExterne));
  }

  /**
   * Lie une salle à un utilisateur.
   *
   * @param userId identifiant de l'utilisateur
   * @param salleId UUID stable de la salle
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  void ajouterPourUtilisateur(final String userId, final UUID salleId) {
    jdbc.update(
        """
        INSERT INTO user_salles (user_id, salle_id)
        VALUES (:userId, CAST(:salleId AS uuid))
        """,
        Map.of(CLE_USER_ID, userId, CLE_SALLE_ID, salleId.toString()));
  }

  /**
   * Supprime la relation utilisateur–salle.
   *
   * @param userId identifiant de l'utilisateur
   * @param salleId UUID stable de la salle
   * @return le nombre de lignes réellement supprimées (0 si la salle n'était
   *     pas suivie)
   */
  /* package-private : accès SQL réservé à SalleService (même package), pattern
   * service/repository. */
  int supprimerPourUtilisateur(final String userId, final UUID salleId) {
    return jdbc.update(
        """
        DELETE FROM user_salles
        WHERE user_id = :userId AND salle_id = CAST(:salleId AS uuid)
        """,
        Map.of(CLE_USER_ID, userId, CLE_SALLE_ID, salleId.toString()));
  }
}
