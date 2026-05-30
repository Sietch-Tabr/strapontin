/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.decouverte;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de découverte de concerts par département.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class DecouverteService {

  private static final Logger log = LoggerFactory.getLogger(DecouverteService.class);
  private static final int CONCERTS_PAR_PAGE = 100;
  private static final String CLE_DEPTS = "depts";

  private final NamedParameterJdbcTemplate jdbc;

  /// @param jdbc accès JDBC
  public DecouverteService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /// Retourne les concerts filtrés par département et critères.
  ///
  /// @param userId         identifiant de l'utilisateur (pour lire ses départements par défaut)
  /// @param depts          liste de départements (null = utiliser ceux de l'utilisateur)
  /// @param q              terme de recherche
  /// @param mois           filtre mois YYYY-MM
  /// @param genre          filtre genre
  /// @param classification filtre classification
  /// @param tri            {@code "date"} ou {@code "nouveaute"}
  /// @param page           numéro de page (0-indexé)
  /// @return réponse paginée avec filtres disponibles
  public ReponseDecouverte decouvrir(
      String userId,
      List<String> depts,
      String q,
      String mois,
      String genre,
      String classification,
      String tri,
      int page) {

    LocalDate aujourd8 = LocalDate.now();
    int pageNum = Math.max(0, page);

    List<String> deptsUtilisateur = lireDeptsUtilisateur(userId);
    List<String> deptsActifs = (depts == null || depts.isEmpty()) ? deptsUtilisateur : depts;

    Map<String, Object> params = new HashMap<>();
    params.put("limit", CONCERTS_PAR_PAGE);
    params.put("offset", pageNum * CONCERTS_PAR_PAGE);
    String rechercheNorm = q.strip().substring(0, Math.min(q.strip().length(), 100));
    String genreStr = genre.strip().substring(0, Math.min(genre.strip().length(), 50));
    StringBuilder where =
        construireFiltresWhere(
            deptsActifs, rechercheNorm, mois, genreStr, classification, aujourd8, params);

    String orderBy =
        "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";

    List<ConcertDecouverte> concerts =
        jdbc.query(
            """
            SELECT c.id, c.artiste, c.date::text, c.heure::text, c.salle, c.ville,
                   c.dept, c.url, c.classification, c.genre, c.statut, c.first_seen
            FROM concerts c
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

    Map<String, Object> countParams = new HashMap<>(params);
    countParams.remove("limit");
    countParams.remove("offset");
    int total =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM concerts c WHERE " + where, countParams, Integer.class),
            0);

    Map<String, Object> baseParams = new HashMap<>();
    baseParams.put("aujourd8", aujourd8);
    StringBuilder whereBase = new StringBuilder("c.date >= :aujourd8 AND c.disponible = true");
    if (!deptsActifs.isEmpty()) {
      whereBase.append(" AND c.dept = ANY(:depts)");
      baseParams.put(CLE_DEPTS, deptsActifs.toArray(String[]::new));
    }

    List<String> genresDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT c.genre FROM concerts c WHERE "
                + whereBase
                + " AND c.genre IS NOT NULL ORDER BY c.genre",
            baseParams,
            String.class);
    List<String> classificationsDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT c.classification FROM concerts c WHERE "
                + whereBase
                + " AND c.classification IS NOT NULL ORDER BY c.classification",
            baseParams,
            String.class);
    List<String> moisDisponibles =
        jdbc.queryForList(
            "SELECT DISTINCT to_char(c.date, 'YYYY-MM') FROM concerts c WHERE "
                + whereBase
                + " ORDER BY 1",
            baseParams,
            String.class);

    java.sql.Timestamp syncTs =
        jdbc.queryForObject(
            "SELECT MAX(derniere_exec) FROM fetch_jobs WHERE type_job = 'fetch:region'"
                + " AND derniere_exec IS NOT NULL",
            Map.of(),
            java.sql.Timestamp.class);

    List<SyncDept> syncParDept = List.of();
    if (!deptsActifs.isEmpty()) {
      syncParDept =
          jdbc.query(
              "SELECT dept, MAX(updated_at) AS derniere_sync FROM concerts"
                  + " WHERE dept = ANY(:depts) GROUP BY dept",
              Map.of(CLE_DEPTS, deptsActifs.toArray(String[]::new)),
              (rs, _) -> {
                java.sql.Timestamp ts = rs.getTimestamp("derniere_sync");
                return new SyncDept(rs.getString("dept"), ts != null ? ts.toInstant() : null);
              });
    }

    return new ReponseDecouverte(
        concerts,
        pageNum,
        Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE)),
        total,
        genresDisponibles,
        classificationsDisponibles,
        moisDisponibles,
        deptsUtilisateur,
        syncTs != null ? syncTs.toInstant() : null,
        syncParDept);
  }

  /// Déclenche un fetch régional via le worker.
  ///
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  public ResponseEntity<Map<String, Object>> fetcher() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:8081/trigger/tout"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> rep = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (rep.statusCode() == 200) return ResponseEntity.ok(Map.of("statut", "déclenché"));
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible (statut " + rep.statusCode() + ").");
      return ResponseEntity.status(503).body(erreur);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Requête interrompue.");
      return ResponseEntity.status(503).body(erreur);
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger (découverte)", e);
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible.");
      return ResponseEntity.status(503).body(erreur);
    }
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
    if (!mois.isBlank() && mois.matches("\\d{4}-\\d{2}")) {
      LocalDate debutMois = LocalDate.parse(mois + "-01");
      where.append(" AND c.date >= :moisDebut AND c.date < :moisFin");
      params.put("moisDebut", debutMois);
      params.put("moisFin", debutMois.plusMonths(1));
    }
    if (!genreStr.isEmpty()) {
      where.append(" AND c.genre ILIKE :genre");
      params.put("genre", genreStr);
    }
    if (!classification.isBlank()) {
      where.append(" AND c.classification = :classification");
      params.put("classification", classification);
    }
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
}
