/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import static fr.strapontin.web.concert.FiltreConcertsBuilder.CONCERTS_PAR_PAGE;

import fr.strapontin.web.concert.FiltreConcerts;
import fr.strapontin.web.concert.FiltreConcertsBuilder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Service de découverte de concerts par département.
 *
 * <p>Les requêtes indépendantes (COUNT, filtres disponibles, sync) sont parallélisées via {@link
 * CompletableFuture} sur les threads virtuels Spring.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class DecouverteService {

  private static final Logger log = LoggerFactory.getLogger(DecouverteService.class);
  private static final String CLE_DEPTS = "depts";

  private final NamedParameterJdbcTemplate jdbc;
  private final RestClient restClient;

  /**
   * @param jdbc accès JDBC
   * @param restClient client HTTP partagé (module web), pour déclencher le worker
   */
  public DecouverteService(NamedParameterJdbcTemplate jdbc, RestClient restClient) {
    this.jdbc = jdbc;
    this.restClient = restClient;
  }

  /**
   * Retourne les concerts filtrés par département et critères.
   *
   * <p>COUNT(*), filtres disponibles et données de synchronisation sont calculés en parallèle avec
   * la requête principale pour réduire les round-trips séquentiels.
   *
   * @param userId identifiant de l'utilisateur (pour lire ses départements par défaut)
   * @param filtre critères de filtrage ; {@code depts} null = utiliser ceux de l'utilisateur
   * @return réponse paginée avec filtres disponibles
   */
  public ReponseDecouverte decouvrir(String userId, FiltreConcerts filtre) {
    LocalDate aujourd8 = LocalDate.now();
    int pageNum = Math.max(0, filtre.page());

    List<String> deptsUtilisateur = lireDeptsUtilisateur(userId);
    List<String> deptsActifs =
        (filtre.depts() == null || filtre.depts().isEmpty()) ? deptsUtilisateur : filtre.depts();

    Map<String, Object> params = new HashMap<>();
    params.put("limit", CONCERTS_PAR_PAGE);
    params.put("offset", pageNum * CONCERTS_PAR_PAGE);
    String rechercheNorm = FiltreConcertsBuilder.tronquer(filtre.q(), 100);
    String genreStr = FiltreConcertsBuilder.tronquer(filtre.genre(), 50);
    StringBuilder where =
        construireFiltresWhere(
            deptsActifs,
            rechercheNorm,
            filtre.mois(),
            genreStr,
            filtre.classification(),
            aujourd8,
            params);

    String orderBy = FiltreConcertsBuilder.clauseTri(filtre.tri());

    Map<String, Object> baseParams = new HashMap<>();
    baseParams.put("aujourd8", aujourd8);
    StringBuilder whereBase = new StringBuilder("c.date >= :aujourd8 AND c.disponible = true");
    if (!deptsActifs.isEmpty()) {
      whereBase.append(" AND c.dept = ANY(:depts)");
      baseParams.put(CLE_DEPTS, deptsActifs.toArray(String[]::new));
    }

    Map<String, Object> countParams = new HashMap<>(params);
    countParams.remove("limit");
    countParams.remove("offset");

    // Requêtes indépendantes lancées en parallèle pendant l'exécution de la requête principale
    var futureTotal = CompletableFuture.supplyAsync(() -> compterTotal(where, countParams));
    var futureFiltres =
        CompletableFuture.supplyAsync(() -> chargerFiltresDisponibles(whereBase, baseParams));
    var futureSyncTs = CompletableFuture.supplyAsync(this::lireDerniereSync);
    var futureSyncParDept = CompletableFuture.supplyAsync(() -> lireSyncParDept(deptsActifs));

    List<ConcertDecouverte> concerts =
        jdbc.query(
            """
            SELECT c.id, COALESCE(a.nom, c.artiste) AS artiste, c.date::text, c.heure::text,
                   c.salle, c.ville, c.dept, c.url, c.classification, c.genre, c.statut, c.first_seen
            FROM concerts c
            LEFT JOIN artistes a ON a.id = c.artiste_id
            WHERE %s
            ORDER BY %s
            LIMIT :limit OFFSET :offset
            """
                .formatted(where, orderBy),
            params,
            (rs, _) ->
                new ConcertDecouverte(
                    rs.getString("id"),
                    rs.getString("artiste"),
                    rs.getString("date"),
                    rs.getString("heure"),
                    rs.getString("salle"),
                    rs.getString("ville"),
                    rs.getString("dept"),
                    rs.getString("url"),
                    rs.getString("classification"),
                    rs.getString("genre"),
                    rs.getString("statut"),
                    rs.getTimestamp("first_seen") != null
                        ? rs.getTimestamp("first_seen").toInstant()
                        : null));

    int total = futureTotal.join();
    FiltresDisponibles filtres = futureFiltres.join();
    java.sql.Timestamp syncTs = futureSyncTs.join();
    List<SyncDept> syncParDept = futureSyncParDept.join();

    return new ReponseDecouverte(
        concerts,
        pageNum,
        Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE)),
        total,
        filtres.genres(),
        filtres.classifications(),
        filtres.mois(),
        deptsUtilisateur,
        syncTs != null ? syncTs.toInstant() : null,
        syncParDept);
  }

  /**
   * Déclenche un fetch régional via le worker.
   *
   * @return 200 si déclenché, 503 si le worker est inaccessible
   */
  public ResponseEntity<Map<String, Object>> fetcher() {
    try {
      restClient.post().uri("http://localhost:8081/trigger/tout").retrieve().toBodilessEntity();
      return ResponseEntity.ok(Map.of("statut", "déclenché"));
    } catch (RestClientResponseException e) {
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible (statut " + e.getStatusCode().value() + ").");
      return ResponseEntity.status(503).body(erreur);
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger (découverte)", e);
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible.");
      return ResponseEntity.status(503).body(erreur);
    }
  }

  private int compterTotal(StringBuilder where, Map<String, Object> params) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM concerts c WHERE " + where, new HashMap<>(params), Integer.class),
        0);
  }

  private FiltresDisponibles chargerFiltresDisponibles(
      StringBuilder whereBase, Map<String, Object> baseParams) {
    String sql =
        """
        WITH base AS (
          SELECT c.genre, to_char(c.date, 'YYYY-MM') AS mois, c.classification
          FROM concerts c
          WHERE %s
        )
        SELECT 'genre'          AS type, genre          AS valeur
          FROM (SELECT DISTINCT genre FROM base WHERE genre IS NOT NULL) g
        UNION ALL
        SELECT 'mois'           AS type, mois           AS valeur
          FROM (SELECT DISTINCT mois FROM base) m
        UNION ALL
        SELECT 'classification' AS type, classification AS valeur
          FROM (SELECT DISTINCT classification FROM base WHERE classification IS NOT NULL) cl
        ORDER BY type, valeur
        """
            .formatted(whereBase);

    List<String> genres = new ArrayList<>();
    List<String> mois = new ArrayList<>();
    List<String> classifications = new ArrayList<>();
    jdbc.query(
        sql,
        new HashMap<>(baseParams),
        rs -> {
          switch (rs.getString("type")) {
            case "genre" -> genres.add(rs.getString("valeur"));
            case "mois" -> mois.add(rs.getString("valeur"));
            case "classification" -> classifications.add(rs.getString("valeur"));
          }
        });
    return new FiltresDisponibles(genres, mois, classifications);
  }

  private java.sql.Timestamp lireDerniereSync() {
    return jdbc.queryForObject(
        "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE type_job = 'fetch:region'"
            + " AND derniere_exec IS NOT NULL",
        Map.of(),
        java.sql.Timestamp.class);
  }

  private List<SyncDept> lireSyncParDept(List<String> deptsActifs) {
    if (deptsActifs.isEmpty()) return List.of();
    return jdbc.query(
        "SELECT dept, MAX(updated_at) AS derniere_sync FROM concerts"
            + " WHERE dept = ANY(:depts) GROUP BY dept",
        Map.of(CLE_DEPTS, deptsActifs.toArray(String[]::new)),
        (rs, _) -> {
          java.sql.Timestamp ts = rs.getTimestamp("derniere_sync");
          return new SyncDept(rs.getString("dept"), ts != null ? ts.toInstant() : null);
        });
  }

  private StringBuilder construireFiltresWhere(
      List<String> deptsActifs,
      String rechercheNorm,
      String mois,
      String genreStr,
      String classification,
      LocalDate aujourd8,
      Map<String, Object> params) {
    StringBuilder where = new StringBuilder("c.date >= :aujourd8 AND c.disponible = true");
    params.put("aujourd8", aujourd8);
    if (!deptsActifs.isEmpty()) {
      where.append(" AND c.dept = ANY(:depts)");
      params.put(CLE_DEPTS, deptsActifs.toArray(String[]::new));
    }
    if (!rechercheNorm.isEmpty()) {
      where.append(" AND (c.artiste ILIKE :q OR c.salle ILIKE :q)");
      params.put("q", "%" + rechercheNorm + "%");
    }
    FiltreConcertsBuilder.appliquerMois(where, params, mois);
    FiltreConcertsBuilder.appliquerGenre(where, params, genreStr);
    FiltreConcertsBuilder.appliquerClassification(where, params, classification);
    return where;
  }

  private List<String> lireDeptsUtilisateur(String userId) {
    List<String[]> lignes =
        jdbc.query(
            "SELECT departements FROM user_config WHERE user_id = :userId",
            Map.of("userId", userId),
            (rs, _) -> {
              try {
                return rs.getArray("departements") != null
                    ? (String[]) rs.getArray("departements").getArray()
                    : new String[0];
              } catch (Exception _) {
                return new String[0];
              }
            });
    return lignes.isEmpty() ? List.of() : Arrays.asList(lignes.get(0));
  }

  private record FiltresDisponibles(
      List<String> genres, List<String> mois, List<String> classifications) {}
}
