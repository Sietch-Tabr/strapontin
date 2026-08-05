/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.utilisateur.UserConfigService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/// Service de la page d'accueil — concerts groupés par artiste suivi.
///
/// @since 2.1.0
/// @author Kwaite
@Service
public class AccueilService {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  /// @param jdbc              accès JDBC
  /// @param userConfigService lecture du filtre de disponibilité
  public AccueilService(NamedParameterJdbcTemplate jdbc, UserConfigService userConfigService) {
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  /// Marque comme vus les matches artiste non encore vus pour l'utilisateur.
  ///
  /// <p>Appelé à la visite de l'accueil pour réinitialiser le badge de navigation. Restreint aux
  /// matchs artiste — l'accueil n'affiche jamais les matchs salle (voir {@link #accueil}),
  /// donc marquer aussi ces derniers effaçait à tort l'indicateur « Nouveau » par concert sur
  /// {@code /spectacles} avant même que l'utilisateur n'ait visité cette page.
  ///
  /// @param userId identifiant de l'utilisateur
  public void marquerVus(String userId) {
    jdbc.update(
        """
        UPDATE user_matches um
        SET vu_le = now()
        FROM concerts c
        WHERE um.concert_id = c.id
          AND um.user_id = :userId
          AND um.vu_le IS NULL
          AND EXISTS (
            SELECT 1 FROM user_artistes ua
            WHERE ua.user_id = um.user_id AND c.artiste_id = ua.artiste_id
          )
        """,
        Map.of(CLE_USER_ID, userId));
  }

  /// Retourne les prochains concerts des artistes suivis, groupés par artiste.
  ///
  /// @param userId            identifiant de l'utilisateur
  /// @param masques           {@code true} pour afficher les concerts masqués
  /// @param indisponibles     {@code "1"} pour inclure les concerts non disponibles
  /// @param nouveau           {@code true} pour afficher uniquement les concerts ajoutés
  ///                          en base depuis moins de 7 jours
  /// @return groupes artiste/concerts avec compteurs
  public ReponseAccueil accueil(
      String userId, boolean masques, String indisponibles, boolean nouveau) {
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
      where.append(
          " AND (c.statut IS NULL OR c.statut IN ('onsale', 'presale', 'rescheduled',"
              + " 'postponed'))");
    }
    if (nouveau) {
      where.append(" AND c.first_seen >= (CURRENT_TIMESTAMP - INTERVAL '7 days')");
    }
    where.append(
        """
         AND EXISTS (
           SELECT 1 FROM user_artistes ua
           WHERE ua.user_id = :userId
             AND c.artiste_id = ua.artiste_id
         )
        """);

    Map<String, Object> params = new HashMap<>();
    params.put(CLE_USER_ID, userId);
    params.put("aujourd8", aujourd8);

    // est_nouveau (badge affiché par concert) unifié sur um.vu_le IS NULL — cohérent avec
    // SpectacleDto.estNew et avec l'intention "jamais vu", plutôt que first_seen (fraîcheur en
    // base, indépendante de l'utilisateur) utilisé auparavant ici. Sans lien avec le paramètre
    // `nouveau` de cette méthode (filtre chip "Nouveau", basé sur first_seen — fonctionnalité
    // distincte : concerts récemment détectés, pas concerts jamais vus).
    List<ConcertAccueil> lignes =
        jdbc.query(
            """
            SELECT c.id, COALESCE(a.nom, c.artiste) AS artiste, c.artiste_norm,
                   c.artiste_id::text AS artiste_id, c.date::text,
                   c.heure::text, c.salle, c.ville, c.url, c.statut,
                   um.vu_le IS NULL AS est_nouveau
            FROM user_matches um
            INNER JOIN concerts c ON um.concert_id = c.id
            LEFT JOIN artistes a ON a.id = c.artiste_id
            WHERE %s
            ORDER BY c.date ASC, c.artiste ASC
            """
                .formatted(where),
            params,
            (rs, _) ->
                new ConcertAccueil(
                    rs.getString("id"),
                    rs.getString("artiste"),
                    rs.getString("artiste_norm"),
                    rs.getString("artiste_id"),
                    rs.getString("date"),
                    rs.getString("heure"),
                    rs.getString("salle"),
                    rs.getString("ville"),
                    rs.getString("url"),
                    rs.getString("statut"),
                    rs.getBoolean("est_nouveau")));

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
                      e.getKey(),
                      dates.get(0).artisteNorm(),
                      dates.get(0).artisteId(),
                      dates.size() >= 2,
                      estNew,
                      dates);
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

    return new ReponseAccueil(artistesGroupes, nbArtistes, nbSalles);
  }
}
