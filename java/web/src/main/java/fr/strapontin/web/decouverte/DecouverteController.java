/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.decouverte;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la découverte de concerts par département.
///
/// <p>Retourne les concerts disponibles filtrés par département(s),
/// avec les filtres habituels (q, mois, genre, classification, budget, tri).
/// Pré-sélectionne les départements de l'utilisateur si aucun n'est fourni.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/decouverte")
public class DecouverteController {

  private static final Logger log = LoggerFactory.getLogger(DecouverteController.class);
  private static final int CONCERTS_PAR_PAGE = 100;

  private final NamedParameterJdbcTemplate jdbc;

  public DecouverteController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record ConcertDecouverte(
      String id,
      String artiste,
      String date,
      String heure,
      String salle,
      String ville,
      String dept,
      String url,
      BigDecimal prixMin,
      BigDecimal prixMax,
      String classification,
      String genre,
      String statut,
      Instant firstSeen) {}

  record SyncDept(String dept, Instant derniereExec) {}

  record ReponseDecouverte(
      List<ConcertDecouverte> concerts,
      int page,
      int totalPages,
      int totalConcerts,
      List<String> genresDisponibles,
      List<String> classificationsDisponibles,
      List<String> moisDisponibles,
      List<String> deptsUtilisateur,
      Instant derniereSyncRegion,
      List<SyncDept> syncParDept) {}

  record RequeteFetch(List<String> depts) {}

  /// Retourne les concerts filtrés par département.
  ///
  /// @param depts          liste de départements (2-3 chiffres)
  /// @param q              terme de recherche artiste
  /// @param mois           filtre mois (YYYY-MM)
  /// @param genre          filtre genre
  /// @param classification filtre classification
  /// @param budget         prix max
  /// @param tri            {@code "date"} ou {@code "nouveaute"}
  /// @param page           numéro de page (0-indexé)
  /// @param auth           contexte d'authentification
  /// @return les concerts et les filtres disponibles
  @GetMapping
  public ResponseEntity<ReponseDecouverte> decouvrir(
      @RequestParam(name = "dept", required = false) List<String> depts,
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "") String budget,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "0") int page,
      Authentication auth) {

    String userId = utilisateur(auth).id();
    LocalDate aujourd8 = LocalDate.now();
    int pageNum = Math.max(0, page);

    // Départements de l'utilisateur (pré-sélection)
    List<String> deptsUtilisateur = lireDeptsUtilisateur(userId);

    // Si aucun département fourni → utiliser les depts de l'user
    List<String> deptsActifs = (depts == null || depts.isEmpty()) ? deptsUtilisateur : depts;

    // Construction du WHERE dynamique
    StringBuilder where = new StringBuilder("c.date >= :aujourd8 AND c.disponible = true");
    Map<String, Object> params = new HashMap<>();
    params.put("aujourd8", aujourd8);
    params.put("limit", CONCERTS_PAR_PAGE);
    params.put("offset", pageNum * CONCERTS_PAR_PAGE);

    if (!deptsActifs.isEmpty()) {
      where.append(" AND c.dept = ANY(:depts)");
      params.put("depts", deptsActifs.toArray(String[]::new));
    }

    String rechercheNorm = q.strip().substring(0, Math.min(q.strip().length(), 100));
    if (!rechercheNorm.isEmpty()) {
      where.append(" AND c.artiste ILIKE :q");
      params.put("q", "%" + rechercheNorm + "%");
    }

    if (!mois.isBlank() && mois.matches("\\d{4}-\\d{2}")) {
      where.append(" AND to_char(c.date, 'YYYY-MM') = :mois");
      params.put("mois", mois);
    }

    String genreStr = genre.strip().substring(0, Math.min(genre.strip().length(), 50));
    if (!genreStr.isEmpty()) {
      where.append(" AND c.genre ILIKE :genre");
      params.put("genre", genreStr);
    }

    if (!classification.isBlank()) {
      where.append(" AND c.classification = :classification");
      params.put("classification", classification);
    }

    if (!budget.isBlank() && budget.matches("\\d+")) {
      where.append(" AND c.prix_min::numeric <= :budget");
      params.put("budget", new BigDecimal(budget));
    }

    String orderBy =
        "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";

    String selectSql =
        """
        SELECT c.id, c.artiste, c.date::text, c.heure::text, c.salle, c.ville,
               c.dept, c.url, c.prix_min, c.prix_max, c.classification, c.genre,
               c.statut, c.first_seen
        FROM concerts c
        WHERE %s
        ORDER BY %s
        LIMIT :limit OFFSET :offset
        """
            .formatted(where, orderBy);

    List<ConcertDecouverte> concerts =
        jdbc.query(
            selectSql,
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
                    rs.getBigDecimal("prix_min"),
                    rs.getBigDecimal("prix_max"),
                    rs.getString("classification"),
                    rs.getString("genre"),
                    rs.getString("statut"),
                    rs.getTimestamp("first_seen") != null
                        ? rs.getTimestamp("first_seen").toInstant()
                        : null));

    // Total sans LIMIT
    String countSql = "SELECT COUNT(*) FROM concerts c WHERE " + where;
    Map<String, Object> countParams = new HashMap<>(params);
    countParams.remove("limit");
    countParams.remove("offset");
    int total = jdbc.queryForObject(countSql, countParams, Integer.class);

    // Genres et classifications disponibles (base = même WHERE sans genre/classif)
    Map<String, Object> baseParams = new HashMap<>();
    baseParams.put("aujourd8", aujourd8);
    StringBuilder whereBase = new StringBuilder("c.date >= :aujourd8 AND c.disponible = true");
    if (!deptsActifs.isEmpty()) {
      whereBase.append(" AND c.dept = ANY(:depts)");
      baseParams.put("depts", deptsActifs.toArray(String[]::new));
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

    // Dernière synchro région
    List<Instant> syncLignes =
        jdbc
            .queryForList(
                "SELECT derniere_exec FROM fetch_jobs WHERE type_job = 'region' AND derniere_exec"
                    + " IS NOT NULL",
                Map.of(),
                java.sql.Timestamp.class)
            .stream()
            .map(ts -> ts != null ? ts.toInstant() : null)
            .collect(Collectors.toList());
    Instant derniereSyncRegion = syncLignes.isEmpty() ? null : syncLignes.get(0);

    // Dernière mise à jour par département (depuis concerts.updated_at)
    List<SyncDept> syncParDept = List.of();
    if (!deptsActifs.isEmpty()) {
      syncParDept =
          jdbc.query(
              "SELECT dept, MAX(updated_at) AS derniere_sync FROM concerts"
                  + " WHERE dept = ANY(:depts) GROUP BY dept",
              Map.of("depts", deptsActifs.toArray(String[]::new)),
              (rs, _) -> {
                java.sql.Timestamp ts = rs.getTimestamp("derniere_sync");
                return new SyncDept(rs.getString("dept"), ts != null ? ts.toInstant() : null);
              });
    }

    int totalPages = Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE));
    return ResponseEntity.ok(
        new ReponseDecouverte(
            concerts,
            pageNum,
            totalPages,
            total,
            genresDisponibles,
            classificationsDisponibles,
            moisDisponibles,
            deptsUtilisateur,
            derniereSyncRegion,
            syncParDept));
  }

  /// Déclenche un fetch régional pour les départements fournis.
  ///
  /// @param requete corps JSON avec la liste de départements
  /// @return 200 si déclenché, 503 si le worker est inaccessible
  @PostMapping("/fetcher")
  public ResponseEntity<Map<String, Object>> fetcher(@RequestBody RequeteFetch requete) {
    try {
      java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
      java.net.http.HttpRequest req =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create("http://localhost:8081/trigger/tout"))
              .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
              .build();
      java.net.http.HttpResponse<String> rep =
          client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
      if (rep.statusCode() == 200) {
        return ResponseEntity.ok(Map.of("statut", "déclenché"));
      }
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible (statut " + rep.statusCode() + ").");
      return ResponseEntity.status(503).body(erreur);
    } catch (Exception e) {
      log.warn("Erreur appel worker trigger (découverte)", e);
      Map<String, Object> erreur = new HashMap<>();
      erreur.put("erreur", "Worker inaccessible : " + e.getMessage());
      return ResponseEntity.status(503).body(erreur);
    }
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
              } catch (Exception e) {
                return new String[0];
              }
            });
    return lignes.isEmpty() ? List.of() : Arrays.asList(lignes.get(0));
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
