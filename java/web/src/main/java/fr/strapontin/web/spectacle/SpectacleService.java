/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.spectacle;

import static fr.strapontin.web.concert.FiltreConcertsBuilder.CONCERTS_PAR_PAGE;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.concert.FiltreConcertsBuilder;
import fr.strapontin.web.utilisateur.UserConfigService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de gestion des spectacles suivis par un utilisateur.
 *
 * <p>Requêtes SQL dynamiques avec filtres multiples. Pagination à 100 concerts par page. Les
 * requêtes indépendantes (COUNT, filtres disponibles, suivi) sont parallélisées via {@link
 * CompletableFuture} sur les threads virtuels Spring.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class SpectacleService {

  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  /**
   * @param jdbc accès JDBC
   * @param userConfigService lecture du filtre de disponibilité
   */
  public SpectacleService(final NamedParameterJdbcTemplate jdbc, final UserConfigService userConfigService) {
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  /**
   * Liste les spectacles selon les filtres fournis, paginés à 100 par page.
   *
   * <p>COUNT(*), filtres disponibles et nbSuivi sont calculés en parallèle avec la requête
   * principale pour réduire le nombre de round-trips séquentiels.
   *
   * @param userId identifiant de l'utilisateur
   * @param filtre critères de filtrage et de pagination
   * @return réponse paginée avec filtres disponibles
   */
  public ReponseSpectacles listerSpectacles(final String userId, final FiltreConcerts filtre) {
    final LocalDate aujourd8 = LocalDate.now();
    final int pageNum = Math.max(0, filtre.page());
    final boolean filtreDisponibles = userConfigService.lireFiltreDisponibles(userId);
    final boolean inclureIndisponibles =
        filtre.indisponibles() != null ? "1".equals(filtre.indisponibles()) : !filtreDisponibles;
    final String conditionMasque = filtre.masques() ? "NOT NULL" : "NULL";

    final Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    params.put("aujourd8", aujourd8);
    final StringBuilder whereClause =
        construireWhere(userId, filtre, conditionMasque, inclureIndisponibles, aujourd8, params);

    final String selectSql =
        """
            SELECT c.id, COALESCE(a.nom, c.artiste) AS artiste, c.date::text, c.heure::text,
                   c.salle, c.ville, c.url, c.classification, c.first_seen, c.statut, um.vu_le,
                   c.presale_debut, c.presale_nom, c.absence_detectee_le
            FROM user_matches um
            INNER JOIN concerts c ON um.concert_id = c.id
            LEFT JOIN artistes a ON a.id = c.artiste_id
            WHERE %s
            ORDER BY %s
            LIMIT :limit OFFSET :offset
            """
            .formatted(whereClause, FiltreConcertsBuilder.clauseTri(filtre.tri()));

    final Map<String, Object> paramsPage = new HashMap<>(params);
    paramsPage.put("limit", CONCERTS_PAR_PAGE);
    paramsPage.put("offset", pageNum * CONCERTS_PAR_PAGE);

    final Map<String, Object> baseParams = Map.of("userId", userId, "aujourd8", aujourd8);

    // COUNT et nbSuivi démarrés en parallèle pendant l'exécution de la requête principale
    final var futureTotal = CompletableFuture.supplyAsync(() -> compterTotal(whereClause, params));
    final var futureNbSuivi = CompletableFuture.supplyAsync(() -> compterSuivi(baseParams));

    // Requête principale sur le thread courant pendant que les futures s'exécutent
    final List<SpectacleDto> concerts = jdbc.query(selectSql, paramsPage, (rs, _) -> mapSpectacle(rs));

    final FiltresDisponibles filtresDispos = chargerFiltresDisponibles(conditionMasque, baseParams);

    final int total = futureTotal.join();
    final int nbSuivi = futureNbSuivi.join();

    return new ReponseSpectacles(
        concerts,
        pageNum,
        Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE)),
        total,
        filtresDispos.genres(),
        filtresDispos.mois(),
        filtresDispos.classifications(),
        inclureIndisponibles,
        nbSuivi > 0);
  }

  /**
   * Construit la clause WHERE de la requête principale et remplit les paramètres nommés.
   *
   * @param userId identifiant de l'utilisateur
   * @param filtre critères de filtrage
   * @param conditionMasque {@code "NULL"} ou {@code "NOT NULL"} selon l'affichage des masqués
   * @param inclureIndisponibles inclut les concerts non disponibles
   * @param aujourd8 date du jour
   * @param params paramètres nommés à remplir
   * @return la clause WHERE construite
   */
  private StringBuilder construireWhere(
      final String userId,
      final FiltreConcerts filtre,
      final String conditionMasque,
      final boolean inclureIndisponibles,
      final LocalDate aujourd8,
      final Map<String, Object> params) {

    final StringBuilder where =
        new StringBuilder(
            """
                um.user_id = :userId
                AND c.date >= :aujourd8
                AND um.masque_le IS %s
                """
                .formatted(conditionMasque));

    if (filtre.quinzaine()) {
      where.append(" AND c.date <= :dans15Jours");
      params.put("dans15Jours", aujourd8.plusDays(15));
    }
    if (!inclureIndisponibles) {
      where.append(
          " AND (c.statut IS NULL OR c.statut IN ('onsale', 'presale', 'rescheduled',"
              + " 'postponed'))");
    }

    final String recherche = FiltreConcertsBuilder.tronquer(filtre.q(), 100);
    if (!recherche.isEmpty()) {
      where.append(" AND c.artiste ILIKE :recherche");
      params.put("recherche", "%" + recherche + "%");
    }
    FiltreConcertsBuilder.appliquerMois(where, params, filtre.mois());
    FiltreConcertsBuilder.appliquerClassification(where, params, filtre.classification());
    FiltreConcertsBuilder.appliquerGenre(
        where, params, FiltreConcertsBuilder.tronquer(filtre.genre(), 50));
    appliquerFiltreSources(where, params, userId, filtre.sources());
    return where;
  }

  /**
   * Restreint les résultats aux concerts des artistes ou salles suivis selon les sources actives.
   *
   * @param where clause WHERE en construction
   * @param params paramètres nommés
   * @param userId identifiant de l'utilisateur
   * @param sources sources actives ({@code "artistes"}, {@code "salles"})
   */
  private void appliquerFiltreSources(
      final StringBuilder where, final Map<String, Object> params, final String userId, final List<String> sources) {
    final boolean sourceArtistes = sources == null || sources.isEmpty() || sources.contains("artistes");
    final boolean sourceSalles = sources == null || sources.isEmpty() || sources.contains("salles");

    if (sourceArtistes && !sourceSalles) {
      final List<UUID> ids =
          jdbc.queryForList(
              "SELECT artiste_id FROM user_artistes WHERE user_id = :u",
              Map.of("u", userId),
              UUID.class);
      if (ids.isEmpty()) {
        where.append(" AND false");
      } else {
        where.append(" AND c.artiste_id IN (:artisteIds)");
        params.put("artisteIds", ids);
      }
    } else if (sourceSalles && !sourceArtistes) {
      final List<UUID> ids =
          jdbc.queryForList(
              "SELECT salle_id FROM user_salles WHERE user_id = :u",
              Map.of("u", userId),
              UUID.class);
      if (ids.isEmpty()) {
        where.append(" AND false");
      } else {
        where.append(" AND c.salle_id IN (:salleIds)");
        params.put("salleIds", ids);
      }
    }
  }

  /**
   * Compte le nombre total de matches correspondant aux filtres (pour la pagination).
   *
   * @param whereClause clause WHERE de la requête principale
   * @param params paramètres nommés
   * @return le nombre total de concerts
   */
  private int compterTotal(final StringBuilder whereClause, final Map<String, Object> params) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_matches um INNER JOIN concerts c"
                + " ON um.concert_id = c.id WHERE "
                + whereClause,
            new HashMap<>(params),
            Integer.class),
        0);
  }

  /**
   * Compte le nombre d'artistes et salles suivis par l'utilisateur.
   *
   * @param baseParams paramètres de base ({@code userId})
   * @return le nombre total de suivis
   */
  private int compterSuivi(final Map<String, Object> baseParams) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId)"
                + " + (SELECT COUNT(*) FROM user_salles WHERE user_id = :userId)",
            baseParams,
            Integer.class),
        0);
  }

  /**
   * Charge les valeurs distinctes de genre, mois et classification disponibles (CTE unique).
   *
   * @param conditionMasque {@code "NULL"} ou {@code "NOT NULL"} selon l'affichage des masqués
   * @param baseParams paramètres de base ({@code userId}, {@code aujourd8})
   * @return les filtres disponibles regroupés par type
   */
  private FiltresDisponibles chargerFiltresDisponibles(
      final String conditionMasque, final Map<String, Object> baseParams) {
    final String cteFiltresSql =
        """
            WITH filtres_base AS (
              SELECT c.genre, to_char(c.date, 'YYYY-MM') AS mois, c.classification
              FROM user_matches um
              INNER JOIN concerts c ON um.concert_id = c.id
              WHERE um.user_id = :userId
                AND c.date >= :aujourd8
                AND um.masque_le IS %s
            )
            SELECT 'genre'          AS type, genre          AS valeur
              FROM (SELECT DISTINCT genre FROM filtres_base WHERE genre IS NOT NULL) g
            UNION ALL
            SELECT 'mois'           AS type, mois           AS valeur
              FROM (SELECT DISTINCT mois  FROM filtres_base) m
            UNION ALL
            SELECT 'classification' AS type, classification AS valeur
              FROM (SELECT DISTINCT classification FROM filtres_base
                    WHERE classification IS NOT NULL) cl
            ORDER BY type, valeur
            """
            .formatted(conditionMasque);

    final List<String> genres = new ArrayList<>();
    final List<String> mois = new ArrayList<>();
    final List<String> classifications = new ArrayList<>();
    jdbc.query(
        cteFiltresSql,
        baseParams,
        rs -> {
          switch (rs.getString("type")) {
            case "genre" -> genres.add(rs.getString("valeur"));
            case "mois" -> mois.add(rs.getString("valeur"));
            case "classification" -> classifications.add(rs.getString("valeur"));
          }
        });
    return new FiltresDisponibles(genres, mois, classifications);
  }

  /**
   * Mappe une ligne de résultat vers un {@link SpectacleDto}.
   *
   * @param rs ligne de résultat positionnée
   * @return le DTO correspondant
   * @throws SQLException en cas d'erreur de lecture
   */
  private SpectacleDto mapSpectacle(final ResultSet rs) throws SQLException {
    final java.time.Instant firstSeen =
        rs.getTimestamp("first_seen") != null ? rs.getTimestamp("first_seen").toInstant() : null;
    final java.time.Instant presaleDebut =
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
        rs.getString("presale_nom"),
        rs.getTimestamp("absence_detectee_le") != null);
  }

  /**
   * Masque ou démasque un concert pour l'utilisateur courant.
   *
   * @param userId identifiant de l'utilisateur
   * @param concertId identifiant UUID du concert
   * @param masquer {@code true} pour masquer, {@code false} pour démasquer
   */
  public void modifierMatch(final String userId, final String concertId, final boolean masquer) {
    final String sql =
        masquer
            ? "UPDATE user_matches SET masque_le = now() WHERE user_id = :userId AND concert_id"
            + " = CAST(:concertId AS uuid)"
            : "UPDATE user_matches SET masque_le = NULL WHERE user_id = :userId AND concert_id"
            + " = CAST(:concertId AS uuid)";
    jdbc.update(sql, Map.of("userId", userId, "concertId", concertId));
  }

  /**
   * Masque tous les concerts d'un artiste pour l'utilisateur courant.
   *
   * <p>Filtre sur {@code artiste_id} (référentiel, stable) plutôt que {@code artiste_norm} le
   * worker (réconciliation cross-sources) démontre que {@code artiste_norm} peut diverger pour un
   * même artiste réel selon la source/le lot de fetch ; un filtre sur ce champ texte ne couvrait
   * donc qu'un sous-ensemble des concerts du groupe visuellement affiché sur l'accueil (tous
   * partageant le même {@code artiste_id}, groupés par nom affiché).
   *
   * @param userId identifiant de l'utilisateur
   * @param artisteId UUID stable de l'artiste dans le référentiel
   */
  public void masquerArtiste(final String userId, final UUID artisteId) {
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = now()
        WHERE user_id = :userId
          AND masque_le IS NULL
          AND concert_id IN (
            SELECT id FROM concerts
            WHERE artiste_id = CAST(:artisteId AS uuid) AND date >= :aujourd8
          )
        """,
        Map.of("userId", userId, "artisteId", artisteId.toString(), "aujourd8", LocalDate.now()));
  }

  /**
   * Démasque tous les concerts d'un artiste pour l'utilisateur courant.
   *
   * <p>Filtre sur {@code artiste_id} plutôt que {@code artiste_norm} — voir {@link
   * #masquerArtiste}.
   *
   * @param userId identifiant de l'utilisateur
   * @param artisteId UUID stable de l'artiste dans le référentiel
   */
  public void demasquerArtiste(final String userId, final UUID artisteId) {
    jdbc.update(
        """
        UPDATE user_matches SET masque_le = NULL
        WHERE user_id = :userId
          AND concert_id IN (
            SELECT id FROM concerts
            WHERE artiste_id = CAST(:artisteId AS uuid) AND date >= :aujourd8
          )
        """,
        Map.of("userId", userId, "artisteId", artisteId.toString(), "aujourd8", LocalDate.now()));
  }

  /**
   * Valeurs distinctes disponibles pour les filtres de la page spectacles.
   *
   * @param genres genres présents dans les matches
   * @param mois mois (YYYY-MM) présents
   * @param classifications classifications présentes
   */
  private record FiltresDisponibles(
      List<String> genres, List<String> mois, List<String> classifications) {}
}
