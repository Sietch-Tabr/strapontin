/*
 * Strapontin — concert finder
 * Copyright (C) 2025 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.securite.UtilisateurDetails;
import fr.strapontin.web.utilisateur.UserConfigService;
import fr.strapontin.web.utils.Dates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/// Contrôleur REST pour la page d'accueil — concerts groupés par artiste suivi.
///
/// Retourne les prochains concerts des artistes suivis par l'utilisateur,
/// groupés par artiste. Limite à 50 concerts. `estNew` basé sur
/// `user_matches.notifie_le IS NULL`.
///
/// @since 2.0.0
/// @author Kwaite
@RestController
@RequestMapping("/api/accueil")
public class AccueilController {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  public AccueilController(NamedParameterJdbcTemplate jdbc, UserConfigService userConfigService) {
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  record ConcertAccueil(
      String id,
      String artiste,
      String artisteNorm,
      String date,
      String heure,
      String salle,
      String ville,
      String url,
      String statut,
      boolean estNew) {

    public String dateFormatee() {
      return Dates.formater(date, false);
    }

    public String dateLongue() {
      return Dates.formater(date, true);
    }
  }

  record GroupeArtiste(
      String artiste,
      String artisteNorm,
      boolean estTournee,
      boolean estNew,
      List<ConcertAccueil> concerts) {}

  record ReponseAccueil(
      List<GroupeArtiste> artistesGroupes,
      int nbArtistes,
      int nbSalles) {}

  /// Retourne les prochains concerts des artistes suivis, groupés par artiste.
  ///
  /// @param masques       {@code true} pour afficher les concerts masqués
  /// @param indisponibles {@code "1"} pour inclure les concerts non disponibles
  /// @param auth          contexte d'authentification
  /// @return les groupes artiste/concerts avec compteurs
  @GetMapping
  public ResponseEntity<ReponseAccueil> accueil(
      @RequestParam(required = false, defaultValue = "false") boolean masques,
      @RequestParam(required = false) String indisponibles,
      Authentication auth) {
    String userId = utilisateur(auth).id();
    LocalDate aujourd8 = LocalDate.now();

    boolean filtreDisponibles = userConfigService.lireFiltreDisponibles(userId);
    boolean inclureIndisponibles =
        indisponibles != null ? "1".equals(indisponibles) : !filtreDisponibles;

    StringBuilder where =
        new StringBuilder(
            """
            um.user_id = :userId
            AND c.date >= :aujourd8
            AND um.masque_le IS %s
            """
                .formatted(masques ? "NOT NULL" : "NULL"));

    if (!inclureIndisponibles) {
      where.append(" AND (c.statut IS NULL OR c.statut IN ('onsale', 'rescheduled'))");
    }

    where.append(
        """
         AND EXISTS (
           SELECT 1 FROM user_artistes ua
           WHERE ua.user_id = :userId
             AND (c.artiste_norm = ua.artiste_normalise
                  OR c.artiste_norm LIKE ua.artiste_normalise || ' %')
         )
        """);

    Map<String, Object> params = new HashMap<>();
    params.put(CLE_USER_ID, userId);
    params.put("aujourd8", aujourd8);

    String selectSql =
        """
        SELECT c.id, c.artiste, c.artiste_norm, c.date::text, c.heure::text,
               c.salle, c.ville, c.url, c.statut, um.notifie_le
        FROM user_matches um
        INNER JOIN concerts c ON um.concert_id = c.id
        WHERE %s
        ORDER BY c.date ASC, c.artiste ASC
        """
            .formatted(where);

    List<ConcertAccueil> lignes =
        jdbc.query(
            selectSql,
            params,
            (rs, _) ->
                new ConcertAccueil(
                    rs.getString("id"),
                    rs.getString("artiste"),
                    rs.getString("artiste_norm"),
                    rs.getString("date"),
                    rs.getString("heure"),
                    rs.getString("salle"),
                    rs.getString("ville"),
                    rs.getString("url"),
                    rs.getString("statut"),
                    rs.getTimestamp("notifie_le") == null));

    // Grouper par artiste en conservant l'ordre
    Map<String, List<ConcertAccueil>> groupes = new LinkedHashMap<>();
    for (ConcertAccueil c : lignes) {
      groupes.computeIfAbsent(c.artiste(), _ -> new ArrayList<>()).add(c);
    }

    List<GroupeArtiste> artistesGroupes =
        groupes.entrySet().stream()
            .map(
                e -> {
                  List<ConcertAccueil> dates = e.getValue();
                  boolean estNew = dates.stream().anyMatch(ConcertAccueil::estNew);
                  return new GroupeArtiste(
                      e.getKey(), dates.get(0).artisteNorm(), dates.size() >= 2, estNew, dates);
                })
            .toList();

    int nbArtistes =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
                Map.of(CLE_USER_ID, userId),
                Integer.class),
            0);

    int nbSalles =
        Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
                Map.of(CLE_USER_ID, userId),
                Integer.class),
            0);

    return ResponseEntity.ok(new ReponseAccueil(artistesGroupes, nbArtistes, nbSalles));
  }

  private static UtilisateurDetails utilisateur(Authentication auth) {
    return (UtilisateurDetails) auth.getPrincipal();
  }
}
