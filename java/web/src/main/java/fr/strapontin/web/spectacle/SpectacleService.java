/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import fr.strapontin.web.utilisateur.UserConfigService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de gestion des spectacles suivis par un utilisateur.
///
/// <p>Requêtes SQL dynamiques avec filtres multiples. Pagination à 100 concerts par page.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class SpectacleService {

  private static final int CONCERTS_PAR_PAGE = 100;

  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  /// @param jdbc              accès JDBC
  /// @param userConfigService lecture du filtre de disponibilité
  public SpectacleService(NamedParameterJdbcTemplate jdbc, UserConfigService userConfigService) {
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  /// Liste les spectacles selon les filtres fournis, paginés à 100 par page.
  ///
  /// @param userId            identifiant de l'utilisateur
  /// @param q                 terme de recherche artiste (optionnel)
  /// @param mois              filtre mois YYYY-MM (optionnel)
  /// @param classification    filtre classification (optionnel)
  /// @param genre             filtre genre (optionnel)
  /// @param quinzaine         {@code true} pour limiter aux 15 prochains jours
  /// @param tri               {@code "date"} ou {@code "nouveaute"}
  /// @param masques           {@code true} pour afficher les concerts masqués
  /// @param sources           liste de sources ({@code "artistes"}, {@code "salles"})
  /// @param indisponibles     {@code "1"} pour inclure les concerts non disponibles
  /// @param page              numéro de page (0-indexé)
  /// @return réponse paginée avec filtres disponibles
  public ReponseSpectacles listerSpectacles(
      String userId,
      String q,
      String mois,
      String classification,
      String genre,
      boolean quinzaine,
      String tri,
      boolean masques,
      List<String> sources,
      String indisponibles,
      int page) {

    LocalDate aujourd8 = LocalDate.now();
    int pageNum = Math.max(0, page);
    boolean filtreDisponibles = userConfigService.lireFiltreDisponibles(userId);
    boolean inclureIndisponibles =
        indisponibles != null ? "1".equals(indisponibles) : !filtreDisponibles;
    boolean sourceArtistes = sources == null || sources.isEmpty() || sources.contains("artistes");
    boolean sourceSalles = sources == null || sources.isEmpty() || sources.contains("salles");

    StringBuilder whereClause =
        new StringBuilder(
            """
            um.user_id = :userId
            AND c.date >= :aujourd8
            AND um.masque_le IS %s
            """
                .formatted(masques ? "NOT NULL" : "NULL"));

    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("aujourd8", aujourd8);

    if (quinzaine) {
      whereClause.append(" AND c.date <= :dans15Jours");
      params.put("dans15Jours", aujourd8.plusDays(15));
    }
    if (!inclureIndisponibles) {
      whereClause.append(
          " AND (c.statut IS NULL OR c.statut IN ('onsale', 'presale', 'rescheduled',"
              + " 'postponed'))");
    }

    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));
    if (!recherche.isEmpty()) {
      whereClause.append(" AND c.artiste ILIKE :recherche");
      params.put("recherche", "%" + recherche + "%");
    }
    if (!mois.isBlank() && mois.matches("\\d{4}-\\d{2}")) {
      LocalDate debutMois = LocalDate.parse(mois + "-01");
      whereClause.append(" AND c.date >= :moisDebut AND c.date < :moisFin");
      params.put("moisDebut", debutMois);
      params.put("moisFin", debutMois.plusMonths(1));
    }
    if (!classification.isBlank()) {
      whereClause.append(" AND c.classification = :classification");
      params.put("classification", classification);
    }
    String genreStr = genre.strip().substring(0, Math.min(genre.strip().length(), 50));
    if (!genreStr.isEmpty()) {
      whereClause.append(" AND c.genre ILIKE :genre");
      params.put("genre", genreStr);
    }
    if (sourceArtistes && !sourceSalles) {
      List<String> norms =
          jdbc.queryForList(
              "SELECT artiste_normalise FROM user_artistes WHERE user_id = :u",
              Map.of("u", userId),
              String.class);
      if (norms.isEmpty()) whereClause.append(" AND false");
      else {
        whereClause.append(" AND c.artiste_norm IN (:artisteNorms)");
        params.put("artisteNorms", norms);
      }
    } else if (sourceSalles && !sourceArtistes) {
      List<String> ids =
          jdbc.queryForList(
              "SELECT regexp_replace(lower(salle_id), '[^a-z0-9]', '', 'g') FROM user_salles"
                  + " WHERE user_id = :u",
              Map.of("u", userId),
              String.class);
      if (ids.isEmpty()) whereClause.append(" AND false");
      else {
        whereClause.append(
            " AND regexp_replace(c.salle_norm, '[^a-z0-9]', '', 'g') IN (:salleIds)");
        params.put("salleIds", ids);
      }
    }

    String orderBy =
        "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";

    String selectSql =
        """
        SELECT c.id, c.artiste, c.date::text, c.heure::text, c.salle, c.ville,
               c.url, c.classification, c.first_seen, c.statut, um.vu_le,
               c.presale_debut, c.presale_nom
        FROM user_matches um
        INNER JOIN concerts c ON um.concert_id = c.id
        WHERE %s
        ORDER BY %s
        LIMIT :limit OFFSET :offset
        """
            .formatted(whereClause, orderBy);

    params.put("limit", CONCERTS_PAR_PAGE);
    params.put("offset", pageNum * CONCERTS_PAR_PAGE);

    List<SpectacleDto> concerts =
        jdbc.query(
            selectSql,
            params,
            (rs, _) -> {
              java.time.Instant firstSeen =
                  rs.getTimestamp("first_seen") != null
                      ? rs.getTimestamp("first_seen").toInstant()
                      : null;
              java.time.Instant presaleDebut =
                  rs.getTimestamp("presale_debut") != null
                      ? rs.getTimestamp("presale_debut").toInstant()
                      : null;
              return new SpectacleDto(
                  rs.getString("id"),
                  rs.getString("artiste"),
                  rs.getString("date"),
                  rs.getString("heure"),
                  rs.getString("salle"),
                  rs.getString("ville"),
                  rs.getString("url"),
                  rs.getString("classification"),
                  firstSeen,
                  rs.getString("statut"),
                  rs.getTimestamp("vu_le") == null,
                  presaleDebut,
                  rs.getString("presale_nom"));
            });

    Map<String, Object> countParams = new HashMap<>(params);
    countParams.remove("limit");
    countParams.remove("offset");
    int total =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_matches um INNER JOIN concerts c ON um.concert_id = c.id"
                    + " WHERE "
                    + whereClause,
                countParams,
                Integer.class),
            0);

    String whereBase =
        "um.user_id = :userId AND c.date >= :aujourd8 AND um.masque_le IS "
            + (masques ? "NOT NULL" : "NULL");
    Map<String, Object> baseParams = Map.of("userId", userId, "aujourd8", aujourd8);

    List<String> genresDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT c.genre FROM user_matches um INNER JOIN concerts c ON um.concert_id"
                + " = c.id WHERE "
                + whereBase
                + " AND c.genre IS NOT NULL ORDER BY c.genre",
            baseParams,
            String.class);
    List<String> moisDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT to_char(c.date, 'YYYY-MM') as mois FROM user_matches um INNER JOIN"
                + " concerts c ON um.concert_id = c.id WHERE "
                + whereBase
                + " ORDER BY mois",
            baseParams,
            String.class);
    List<String> classificationsDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT c.classification FROM user_matches um INNER JOIN concerts c ON"
                + " um.concert_id = c.id WHERE "
                + whereBase
                + " AND c.classification IS NOT NULL ORDER BY c.classification",
            baseParams,
            String.class);

    int nbSuivi =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM user_artistes WHERE user_id = :u)"
                    + " + (SELECT COUNT(*) FROM user_salles WHERE user_id = :u)",
                Map.of("u", userId),
                Integer.class),
            0);

    return new ReponseSpectacles(
        concerts,
        pageNum,
        Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE)),
        total,
        genresDisponibles,
        moisDisponibles,
        classificationsDisponibles,
        inclureIndisponibles,
        nbSuivi > 0);
  }

  /// Masque ou démasque un concert pour l'utilisateur courant.
  ///
  /// @param userId    identifiant de l'utilisateur
  /// @param concertId identifiant UUID du concert
  /// @param masquer   {@code true} pour masquer, {@code false} pour démasquer
  public void modifierMatch(String userId, String concertId, boolean masquer) {
    String sql =
        masquer
            ? "UPDATE user_matches SET masque_le = now() WHERE user_id = :userId AND concert_id"
                + " = CAST(:concertId AS uuid)"
            : "UPDATE user_matches SET masque_le = NULL WHERE user_id = :userId AND concert_id"
                + " = CAST(:concertId AS uuid)";
    jdbc.update(sql, Map.of("userId", userId, "concertId", concertId));
  }

  /// Masque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param userId      identifiant de l'utilisateur
  /// @param artisteNorm identifiant normalisé de l'artiste
  public void masquerArtiste(String userId, String artisteNorm) {
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = now()
        WHERE user_id = :userId
          AND masque_le IS NULL
          AND concert_id IN (
            SELECT id FROM concerts
            WHERE artiste_norm = :artisteNorm AND date >= :aujourd8
          )
        """,
        Map.of("userId", userId, "artisteNorm", artisteNorm, "aujourd8", LocalDate.now()));
  }
}
