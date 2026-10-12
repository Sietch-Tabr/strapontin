/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.web.accueil;

import fr.strapontin.web.concert.FiltreConcertsBuilder;
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

/**
 * Service de la page d'accueil — concerts groupés par artiste suivi.
 *
 * @since 2.1.0
 * @author Kwaite
 */
@Service
public class AccueilService {

  private static final String CLE_USER_ID = "userId";

  private final NamedParameterJdbcTemplate jdbc;
  private final UserConfigService userConfigService;

  /**
   * @param jdbc accès JDBC
   * @param userConfigService lecture du filtre de disponibilité
   */
  public AccueilService(
      final NamedParameterJdbcTemplate jdbc, final UserConfigService userConfigService) {
    this.jdbc = jdbc;
    this.userConfigService = userConfigService;
  }

  /**
   * Marque comme vus les matches artiste non encore vus, pour réinitialiser
   * le badge de navigation — restreint aux matchs artiste (l'accueil
   * n'affiche jamais les matchs salle), sans quoi l'indicateur « Nouveau »
   * de {@code /spectacles} s'effaçait avant même la visite de cette page.
   * @param userId identifiant de l'utilisateur
   */
  public void marquerVus(final String userId) {
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
            WHERE ua.user_id = um.user_id AND (c.artiste_id = ua.artiste_id OR EXISTS (SELECT 1 FROM evenement_artistes ea WHERE ea.evenement_id = c.id AND ea.artiste_id = ua.artiste_id))
          )
        """,
        Map.of(CLE_USER_ID, userId));
  }

  /**
   * Prochains concerts des artistes suivis, groupés par artiste.
   * @param userId identifiant de l'utilisateur
   * @param masques concerts masqués plutôt que visibles
   * @param indisponibles "1" inclut, "0" exclut, null suit la préférence
   * @param nouveau concerts détectés depuis moins de 7 jours seulement
   * @return groupes, compteurs, inclusion effective des indisponibles
   */
  public ReponseAccueil accueil(
      final String userId,
      final boolean masques,
      final String indisponibles,
      final boolean nouveau) {
    final LocalDate aujourd8 = LocalDate.now();
    final boolean filtreDisponibles = userConfigService.lireFiltreDisponibles(userId);
    final boolean inclureIndisponibles =
        indisponibles != null ? "1".equals(indisponibles) : !filtreDisponibles;

    final StringBuilder where = construireWhereAccueil(masques, nouveau, inclureIndisponibles);

    final Map<String, Object> params = new HashMap<>();
    params.put(CLE_USER_ID, userId);
    params.put("aujourd8", aujourd8);

    final List<ConcertAccueil> lignes = chargerConcertsAccueil(where, params);
    final List<GroupeArtiste> artistesGroupes = grouperParArtiste(lignes);
    final StringBuilder whereIndisponibles =
        construireWhereAccueil(masques, nouveau, true)
            .append(" AND NOT ")
            .append(FiltreConcertsBuilder.CONDITION_DISPONIBLE);

    return new ReponseAccueil(
        artistesGroupes,
        compterUserArtistes(userId),
        compterUserSalles(userId),
        inclureIndisponibles,
        compterConcerts(whereIndisponibles, params));
  }

  private int compterConcerts(final StringBuilder where, final Map<String, Object> params) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT count(*) FROM user_matches um JOIN concerts c ON c.id = um.concert_id WHERE "
                + where,
            params,
            Integer.class),
        0);
  }

  private StringBuilder construireWhereAccueil(
      final boolean masques, final boolean nouveau, final boolean inclureIndisponibles) {
    final StringBuilder where =
        new StringBuilder(
            """
            um.user_id = :userId
            AND c.date >= :aujourd8
            AND um.masque_le IS %s
            """
                .formatted(masques ? "NOT NULL" : "NULL"));

    if (!inclureIndisponibles) {
      where.append(" AND ").append(FiltreConcertsBuilder.CONDITION_DISPONIBLE);
    }
    if (nouveau) {
      where.append(" AND c.first_seen >= (CURRENT_TIMESTAMP - INTERVAL '7 days')");
    }
    where.append(
        """
         AND EXISTS (
           SELECT 1 FROM user_artistes ua
           WHERE ua.user_id = :userId
             AND (c.artiste_id = ua.artiste_id OR EXISTS (SELECT 1 FROM evenement_artistes ea WHERE ea.evenement_id = c.id AND ea.artiste_id = ua.artiste_id))
         )
        """);
    return where;
  }

  // est_nouveau (badge affiché par concert) unifié sur um.vu_le IS NULL —
  // cohérent avec SpectacleDto.estNew et avec l'intention "jamais vu", plutôt
  // que first_seen (fraîcheur en base, indépendante de l'utilisateur) utilisé
  // auparavant ici. Sans lien avec le paramètre `nouveau` de cette méthode
  // (filtre chip "Nouveau", basé sur first_seen — fonctionnalité distincte :
  // concerts récemment détectés, pas concerts jamais vus).
  private List<ConcertAccueil> chargerConcertsAccueil(
      final StringBuilder where, final Map<String, Object> params) {
    return jdbc.query(
        """
        SELECT c.id, COALESCE(suivi.nom, a.nom, c.artiste) AS artiste,
               COALESCE(suivi.artiste_norm, c.artiste_norm) AS artiste_norm,
               COALESCE(suivi.artiste_id, c.artiste_id)::text AS artiste_id, c.date::text,
               c.heure::text, c.salle, c.ville, c.url, c.statut,
               um.vu_le IS NULL AS est_nouveau,
               CASE WHEN suivi.artiste_id IS NOT NULL THEN COALESCE(a.nom, c.artiste) END
                 AS tete_d_affiche
        FROM user_matches um
        INNER JOIN concerts c ON um.concert_id = c.id
        LEFT JOIN artistes a ON a.id = c.artiste_id
        -- Soirée partagée dont la tête d'affiche n'est pas suivie : classée
        -- sous l'artiste suivi du plateau.
        LEFT JOIN LATERAL (
          SELECT ea.artiste_id, ea.artiste_norm, COALESCE(ai.nom, ea.artiste_nom) AS nom
          FROM evenement_artistes ea
          JOIN user_artistes ui ON ui.artiste_id = ea.artiste_id AND ui.user_id = :userId
          LEFT JOIN artistes ai ON ai.id = ea.artiste_id
          WHERE ea.evenement_id = c.id
            AND NOT EXISTS (SELECT 1 FROM user_artistes ut
                            WHERE ut.user_id = :userId AND ut.artiste_id = c.artiste_id)
          ORDER BY ea.artiste_norm
          LIMIT 1
        ) suivi ON true
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
                rs.getBoolean("est_nouveau"),
                rs.getString("tete_d_affiche")));
  }

  private List<GroupeArtiste> grouperParArtiste(final List<ConcertAccueil> lignes) {
    final Map<String, List<ConcertAccueil>> groupes = new LinkedHashMap<>();
    for (final ConcertAccueil c : lignes) {
      groupes.computeIfAbsent(c.artiste(), _ -> new ArrayList<>()).add(c);
    }

    return groupes.entrySet().stream()
        .map(
            e -> {
              final List<ConcertAccueil> dates = e.getValue();
              final boolean estNew = dates.stream().anyMatch(ConcertAccueil::estNew);
              return new GroupeArtiste(
                  e.getKey(),
                  dates.get(0).artisteNorm(),
                  dates.get(0).artisteId(),
                  dates.size() >= 2,
                  estNew,
                  dates);
            })
        .toList();
  }

  private int compterUserArtistes(final String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_artistes WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }

  private int compterUserSalles(final String userId) {
    return Objects.requireNonNullElse(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_salles WHERE user_id = :userId",
            Map.of(CLE_USER_ID, userId),
            Integer.class),
        0);
  }
}
