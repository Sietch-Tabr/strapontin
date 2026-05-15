/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.spectacle;

import fr.strapontin.web.securite.UtilisateurDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour les spectacles (portage de {@code spectacles/+page.server.ts}).
///
/// <p>Construit une requête SQL dynamique selon les filtres optionnels.
/// Pagination à 100 concerts par page. Badge "Nouveau" basé sur {@code first_seen} < 7 jours.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/spectacles")
public class SpectacleController {

  private static final int CONCERTS_PAR_PAGE = 100;

  private final NamedParameterJdbcTemplate jdbc;

  public SpectacleController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record SpectacleDto(
      String id,
      String artiste,
      String date,
      String heure,
      String salle,
      String ville,
      String url,
      BigDecimal prixMin,
      BigDecimal prixMax,
      String classification,
      Instant firstSeen,
      String statut,
      boolean estNew) {}

  record ReponseSpectacles(
      List<SpectacleDto> concerts,
      int page,
      int totalPages,
      int totalConcerts,
      List<String> genresDisponibles,
      List<String> moisDisponibles) {}

  record RequeteMasque(boolean masquer) {}

  record RequeteMasquerArtiste(String artisteNorm) {}

  /// Liste les spectacles selon les filtres fournis.
  ///
  /// @param q               terme de recherche artiste
  /// @param mois            filtre mois (format YYYY-MM)
  /// @param classification  filtre classification
  /// @param genre           filtre genre
  /// @param quinzaine       {@code true} pour les 15 prochains jours
  /// @param tri             {@code "date"} ou {@code "nouveaute"}
  /// @param masques         {@code true} pour afficher les concerts masqués
  /// @param budget          prix max
  /// @param source          {@code "artistes"} et/ou {@code "salles"} (multi-valeurs)
  /// @param indisponibles   {@code "1"} pour inclure les concerts non disponibles
  /// @param page            numéro de page (0-indexé)
  /// @param auth            contexte d'authentification
  /// @return la liste paginée des spectacles
  @GetMapping
  public ResponseEntity<ReponseSpectacles> listerSpectacles(
      @RequestParam(required = false, defaultValue = "") String q,
      @RequestParam(required = false, defaultValue = "") String mois,
      @RequestParam(required = false, defaultValue = "") String classification,
      @RequestParam(required = false, defaultValue = "") String genre,
      @RequestParam(required = false, defaultValue = "false") boolean quinzaine,
      @RequestParam(required = false, defaultValue = "date") String tri,
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(required = false, defaultValue = "") String budget,
      @RequestParam(name = "source", required = false) List<String> sources,
      @RequestParam(required = false) String indisponibles,
      @RequestParam(required = false, defaultValue = "0") int page,
      Authentication auth) {

    String userId = utilisateur(auth).id();
    String aujourd8 = LocalDate.now().toString();
    String dans15Jours = LocalDate.now().plusDays(15).toString();
    int pageNum = Math.max(0, page);

    // Filtre disponibles (lu depuis user_config, surchargeable via ?indisponibles=1)
    boolean filtreDisponibles = lireFiltreDisponibles(userId);
    boolean inclureIndisponibles =
        indisponibles != null ? "1".equals(indisponibles) : !filtreDisponibles;

    // Filtre source
    boolean sourceArtistes = sources == null || sources.isEmpty() || sources.contains("artistes");
    boolean sourceSalles = sources == null || sources.isEmpty() || sources.contains("salles");

    // Construction dynamique du WHERE
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
      params.put("dans15Jours", dans15Jours);
    }

    if (!inclureIndisponibles) {
      whereClause.append(" AND (c.statut IS NULL OR c.statut IN ('onsale', 'rescheduled'))");
    }

    String recherche = q.strip().substring(0, Math.min(q.strip().length(), 100));
    if (!recherche.isEmpty()) {
      whereClause.append(" AND c.artiste ILIKE :recherche");
      params.put("recherche", "%" + recherche + "%");
    }

    if (!mois.isBlank() && mois.matches("\\d{4}-\\d{2}")) {
      whereClause.append(" AND to_char(c.date, 'YYYY-MM') = :mois");
      params.put("mois", mois);
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

    if (!budget.isBlank() && budget.matches("\\d+")) {
      whereClause.append(" AND c.prix_min::numeric <= :budget");
      params.put("budget", new BigDecimal(budget));
    }

    // Filtre source artistes/salles
    if (sourceArtistes && !sourceSalles) {
      List<String> norms =
          jdbc.queryForList(
              "SELECT artiste_normalise FROM user_artistes WHERE user_id = :u",
              Map.of("u", userId),
              String.class);
      if (norms.isEmpty()) {
        whereClause.append(" AND false");
      } else {
        whereClause.append(" AND c.artiste_norm IN (:artisteNorms)");
        params.put("artisteNorms", norms);
      }
    } else if (sourceSalles && !sourceArtistes) {
      List<String> ids =
          jdbc.queryForList(
              "SELECT salle_id FROM user_salles WHERE user_id = :u",
              Map.of("u", userId),
              String.class);
      if (ids.isEmpty()) {
        whereClause.append(" AND false");
      } else {
        whereClause.append(" AND c.salle IN (:salleIds)");
        params.put("salleIds", ids);
      }
    }

    String orderBy =
        "nouveaute".equals(tri) ? "c.first_seen DESC, c.date ASC" : "c.date ASC, c.artiste ASC";

    String selectSql =
        """
        SELECT c.id, c.artiste, c.date::text, c.heure::text, c.salle, c.ville,
               c.url, c.prix_min, c.prix_max, c.classification, c.first_seen, c.statut,
               um.notifie_le
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
              Instant firstSeen =
                  rs.getTimestamp("first_seen") != null
                      ? rs.getTimestamp("first_seen").toInstant()
                      : null;
              boolean estNew = rs.getTimestamp("notifie_le") == null;
              return new SpectacleDto(
                  rs.getString("id"),
                  rs.getString("artiste"),
                  rs.getString("date"),
                  rs.getString("heure"),
                  rs.getString("salle"),
                  rs.getString("ville"),
                  rs.getString("url"),
                  rs.getBigDecimal("prix_min"),
                  rs.getBigDecimal("prix_max"),
                  rs.getString("classification"),
                  firstSeen,
                  rs.getString("statut"),
                  estNew);
            });

    // Total sans LIMIT
    String countSql =
        """
        SELECT COUNT(*)
        FROM user_matches um
        INNER JOIN concerts c ON um.concert_id = c.id
        WHERE %s
        """
            .formatted(whereClause);
    Map<String, Object> countParams = new HashMap<>(params);
    countParams.remove("limit");
    countParams.remove("offset");
    int total = jdbc.queryForObject(countSql, countParams, Integer.class);

    // Genres et mois disponibles (sans filtre genre/mois/classification/recherche)
    String whereBase =
        """
        um.user_id = :userId AND c.date >= :aujourd8 AND um.masque_le IS %s
        """
            .formatted(masques ? "NOT NULL" : "NULL");
    Map<String, Object> baseParams = Map.of("userId", userId, "aujourd8", aujourd8);

    List<String> genresDisponibles =
        jdbc.queryForList(
            """
            SELECT DISTINCT c.genre
            FROM user_matches um
            INNER JOIN concerts c ON um.concert_id = c.id
            WHERE %s AND c.genre IS NOT NULL
            ORDER BY c.genre
            """
                .formatted(whereBase),
            baseParams,
            String.class);

    List<String> moisDisponibles =
        jdbc.queryForList(
            """
            SELECT DISTINCT to_char(c.date, 'YYYY-MM') as mois
            FROM user_matches um
            INNER JOIN concerts c ON um.concert_id = c.id
            WHERE %s
            ORDER BY mois
            """
                .formatted(whereBase),
            baseParams,
            String.class);

    int totalPages = Math.max(1, (int) Math.ceil((double) total / CONCERTS_PAR_PAGE));
    return ResponseEntity.ok(
        new ReponseSpectacles(
            concerts, pageNum, totalPages, total, genresDisponibles, moisDisponibles));
  }

  /// Masque ou démasque un concert pour l'utilisateur courant.
  ///
  /// @param concertId identifiant UUID du concert
  /// @param requete   corps JSON avec {@code masquer} (true = masquer, false = démasquer)
  /// @param auth      contexte d'authentification
  /// @return 200 si mis à jour
  @PatchMapping("/matches/{concertId}")
  public ResponseEntity<Map<String, Object>> modifierMatch(
      @PathVariable String concertId, @RequestBody RequeteMasque requete, Authentication auth) {
    String userId = utilisateur(auth).id();

    if (requete.masquer()) {
      jdbc.update(
          """
          UPDATE user_matches SET masque_le = now()
          WHERE user_id = :userId AND concert_id = :concertId::uuid
          """,
          Map.of("userId", userId, "concertId", concertId));
    } else {
      jdbc.update(
          """
          UPDATE user_matches SET masque_le = NULL
          WHERE user_id = :userId AND concert_id = :concertId::uuid
          """,
          Map.of("userId", userId, "concertId", concertId));
    }

    return ResponseEntity.ok(Map.of("succes", true));
  }

  /// Masque tous les concerts d'un artiste pour l'utilisateur courant.
  ///
  /// @param requete corps JSON avec {@code artisteNorm} l'identifiant normalisé de l'artiste
  /// @param auth    contexte d'authentification
  /// @return 200 si mis à jour
  @PostMapping("/masquer-artiste")
  public ResponseEntity<Map<String, Object>> masquerArtiste(
      @RequestBody RequeteMasquerArtiste requete, Authentication auth) {
    String userId = utilisateur(auth).id();
    String aujourd8 = LocalDate.now().toString();

    if (requete.artisteNorm() == null || requete.artisteNorm().isBlank()) {
      return erreur(400, "Identifiant artiste manquant.");
    }

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
        Map.of("userId", userId, "artisteNorm", requete.artisteNorm(), "aujourd8", aujourd8));

    return ResponseEntity.ok(Map.of("succes", true));
  }

  private boolean lireFiltreDisponibles(String userId) {
    List<Boolean> lignes =
        jdbc.queryForList(
            "SELECT filtre_disponibles FROM user_config WHERE user_id = :userId",
            Map.of("userId", userId),
            Boolean.class);
    return lignes.isEmpty() ? true : lignes.get(0);
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }

  private static ResponseEntity<Map<String, Object>> erreur(int status, String message) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("erreur", message);
    return ResponseEntity.status(status).body(corps);
  }
}
