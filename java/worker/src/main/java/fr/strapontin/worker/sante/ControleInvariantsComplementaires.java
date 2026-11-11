/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.EchecPush;
import fr.strapontin.core.TitresDeSpectacle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Invariants de données complémentaires : secrets TOTP, envois push,
 * annulations, hommages et séances déclarées dans deux salles.
 * @since 3.1.1
 * @author Kwaite
 */
final class ControleInvariantsComplementaires {

  private static final String TYPE_ENTITE_GLOBAL = "invariant";
  private static final String EVENEMENT = "evenement";
  private static final String DATE = "date";
  private static final String JUMEAU = "jumeau";
  private static final String SALLE = "salle";
  // Au-delà : théâtre à plusieurs salles internes, créneaux doubles légitimes.
  private static final int SEUIL_CRENEAUX_SALLE_MULTIPLE = 4;

  // Deux salles d'une même ville plus éloignées ne jouent pas la même production.
  private static final int DISTANCE_MAX_SALLES_METRES = 2000;

  private static final String SQL_SEANCES_DANS_DEUX_SALLES =
      """
        SELECT CASE WHEN e1.first_seen >= e2.first_seen THEN e1.id ELSE e2.id END AS recent,
               CASE WHEN e1.first_seen >= e2.first_seen THEN e2.id ELSE e1.id END AS jumeau,
               e1.artiste_norm, e1.date::text AS date,
               coalesce(sa.nom, '?') AS salle_a, coalesce(sb.nom, '?') AS salle_b,
               e1.heure::text AS heure_a, e2.heure::text AS heure_b, (sa.id = sb.id) AS meme_salle
        FROM evenements e1
        JOIN evenements e2 ON e2.id > e1.id AND e2.date = e1.date
         AND e2.heure IS NOT NULL AND e1.heure IS NOT NULL
         AND abs(extract(epoch FROM (e2.heure - e1.heure))) <= 10800
         AND (e2.artiste_norm = e1.artiste_norm
              OR (e1.artiste_id IS NOT NULL AND e2.artiste_id = e1.artiste_id))
        JOIN salles sa ON sa.id = e1.salle_id
        JOIN salles sb ON sb.id = e2.salle_id
        WHERE e1.date >= CURRENT_DATE
          AND (sa.id = sb.id OR sa.salle_parent_id = sb.id OR sb.salle_parent_id = sa.id
               OR (lower(sa.ville) = lower(sb.ville)
                   AND (sa.latitude IS NULL OR sa.longitude IS NULL
                        OR sb.latitude IS NULL OR sb.longitude IS NULL
                        OR 2 * 6371000 * asin(sqrt(
                             power(sin(radians(sb.latitude - sa.latitude) / 2), 2)
                             + cos(radians(sa.latitude)) * cos(radians(sb.latitude))
                               * power(sin(radians(sb.longitude - sa.longitude) / 2), 2)))
                           <= :distanceMax)))
          AND NOT (sa.id = sb.id AND e1.heure = e2.heure)
          AND (sa.id <> sb.id OR NOT EXISTS (
            SELECT 1 FROM observations o1 JOIN observations o2 ON o2.source = o1.source
            WHERE o1.evenement_id = e1.id AND o2.evenement_id = e2.id))
          AND NOT EXISTS (
            SELECT 1 FROM salles_paires_distinctes d
            WHERE d.salle_a = LEAST(sa.id, sb.id) AND d.salle_b = GREATEST(sa.id, sb.id))
          AND EXISTS (SELECT 1 FROM observations o
                      WHERE o.evenement_id IN (e1.id, e2.id)
                        AND o.updated_at > now() - interval '7 days')
        """;

  private final NamedParameterJdbcTemplate jdbc;
  private final PublicationAnomalies publication;

  /* package-private : instancié par ControleInvariantsDonnees. */
  ControleInvariantsComplementaires(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.publication = new PublicationAnomalies(signalements);
  }

  /**
   * @return nombre d'anomalies constatées, tous ces invariants confondus
   */
  /* package-private : appelé par ControleInvariantsDonnees. */
  int verifier() {
    return publication.publier("hommage_rattache_artiste", EVENEMENT, hommagesRattachesAUnArtiste())
        + publication.publier("annulation_une_billetterie", EVENEMENT, annulationsUneBilletterie())
        + publication.publier("secret_totp_non_chiffre", "utilisateur", secretsTotpNonChiffres())
        + publication.publier("envois_push_en_panne", TYPE_ENTITE_GLOBAL, envoisPushEnPanne())
        + publication.publier("seance_dans_deux_salles", EVENEMENT, seancesDansDeuxSalles())
        + publication.publier("creneau_double_meme_source", EVENEMENT, creneauxDoublesMemeSource());
  }

  // L'adresse annonce un hommage, la fiche reste rattachée à l'artiste imité.
  private Map<UUID, Map<String, Object>> hommagesRattachesAUnArtiste() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT e.id, e.artiste_norm, e.date FROM evenements e
        WHERE e.date >= CURRENT_DATE AND e.artiste_id IS NOT NULL
          AND EXISTS (SELECT 1 FROM observations o
                      WHERE o.evenement_id = e.id AND o.url ~* '(tribute|hommage)')
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              UUID.fromString(rs.getString("id")),
              Map.of("artisteNorm", rs.getString("artiste_norm"), DATE, rs.getString(DATE)));
        });
    return anomalies;
  }

  // Une séance à venir annulée par une source alors qu'une autre la présente
  // encore,
  // observations de moins de trois jours des deux côtés.
  private Map<UUID, Map<String, Object>> annulationsUneBilletterie() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT e.id, e.artiste_norm, e.date,
               (SELECT string_agg(DISTINCT o.source::text, ', ') FROM observations o
                 WHERE o.evenement_id = e.id AND o.statut = 'cancelled'
                   AND o.updated_at > now() - interval '3 days') AS annulee_par,
               (SELECT string_agg(DISTINCT o.source::text, ', ') FROM observations o
                 WHERE o.evenement_id = e.id AND o.statut IS DISTINCT FROM 'cancelled'
                   AND o.updated_at > now() - interval '3 days') AS encore_vue_par
        FROM evenements e
        WHERE e.date >= CURRENT_DATE
        """,
        Map.of(),
        rs -> {
          if (rs.getString("annulee_par") != null && rs.getString("encore_vue_par") != null) {
            anomalies.put(
                UUID.fromString(rs.getString("id")),
                Map.of(
                    "artisteNorm",
                    rs.getString("artiste_norm"),
                    DATE,
                    rs.getString(DATE),
                    "annuleePar",
                    rs.getString("annulee_par"),
                    "encoreVuePar",
                    rs.getString("encore_vue_par")));
          }
        });
    return anomalies;
  }

  // Tous les envois vers un même service push échouent depuis des jours pour au
  // moins deux
  // comptes : la cause est côté application ou service, et aucun appareil n'est
  // retiré.
  private Map<UUID, Map<String, Object>> envoisPushEnPanne() {
    final Map<String, Object> services = new LinkedHashMap<>();
    jdbc.query(
        """
        WITH abonnements AS (
          SELECT substring(endpoint from '^https?://[^/]+') AS service, user_id,
                 echecs_consecutifs > 0 AS en_echec, premier_echec_le, dernier_envoi_reussi_le
          FROM push_abonnements)
        SELECT service, count(DISTINCT user_id) FILTER (WHERE en_echec) AS comptes
        FROM abonnements
        GROUP BY service
        HAVING count(DISTINCT user_id) FILTER (WHERE en_echec) >= 2
           AND min(premier_echec_le) FILTER (WHERE en_echec)
               < now() - :jours * interval '1 day'
           AND (max(dernier_envoi_reussi_le) IS NULL
                OR max(dernier_envoi_reussi_le)
                   < min(premier_echec_le) FILTER (WHERE en_echec))
        """,
        Map.of("jours", EchecPush.JOURS_MIN_SERIE_ECHECS),
        rs -> {
          services.put(rs.getString("service"), rs.getInt("comptes") + " comptes");
        });
    return PublicationAnomalies.globale("envois_push_en_panne", services);
  }

  // Un secret chiffré est un base64 d'au moins 28 octets ; un Base32 brut ou
  // toute autre
  // forme est un secret resté en clair (ou corrompu). Le motif évite de
  // déchiffrer.
  private Map<UUID, Map<String, Object>> secretsTotpNonChiffres() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT id FROM users
        WHERE two_factor_secret IS NOT NULL
          AND (two_factor_secret ~ '^[A-Z2-7]+=*$'
               OR two_factor_secret !~ '^[A-Za-z0-9+/]{40,}={0,2}$')
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              PublicationAnomalies.uuidDe(rs.getString("id")),
              Map.of("utilisateurId", rs.getString("id")));
        });
    return anomalies;
  }

  // Même concert sur deux salles déclarées distinctes (admin ou répartition) :
  // un lieu resté sur la mauvaise salle l'a recréé. Le plus récent est signalé.
  // Même artiste, même date, heures à moins de trois heures : dans une salle et
  // sa sous-salle,
  // dans deux salles d'une même commune (séance déplacée restée publiée sous
  // l'ancienne) ou
  // dans une même salle sous deux heures sans billetterie commune (deux séances
  // d'une même billetterie sont deux séances réelles). Les paires
  // déclarées distinctes
  // relèvent de concert_double_salles_distinctes.
  private Map<UUID, Map<String, Object>> seancesDansDeuxSalles() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        SQL_SEANCES_DANS_DEUX_SALLES,
        Map.of("distanceMax", DISTANCE_MAX_SALLES_METRES),
        rs -> {
          final boolean memeSalle = rs.getBoolean("meme_salle");
          anomalies.put(
              rs.getObject("recent", UUID.class),
              Map.of(
                  "artiste",
                  rs.getString("artiste_norm"),
                  DATE,
                  rs.getString(DATE),
                  memeSalle ? "salle" : "salles",
                  memeSalle
                      ? rs.getString("salle_a")
                      : rs.getString("salle_a") + " / " + rs.getString("salle_b"),
                  "heures",
                  rs.getString("heure_a") + " / " + rs.getString("heure_b"),
                  JUMEAU,
                  rs.getObject(JUMEAU, UUID.class).toString()));
        });
    return anomalies;
  }

  // Deux fiches de la même billetterie au même créneau et dans la même salle,
  // sans artiste reconnu : un seul lieu ne reçoit pas deux concerts à la fois,
  // mais la billetterie peut vendre un même concert sous plusieurs programmes.
  // Détecté seulement, jamais fusionné. Une salle qui en cumule plusieurs a
  // plusieurs salles internes : ses créneaux doubles ne sont pas signalés.
  private Map<UUID, Map<String, Object>> creneauxDoublesMemeSource() {
    final List<Map<String, Object>> paires =
        jdbc.queryForList(
            """
            WITH ev AS (
              SELECT e.id, e.salle_id, e.date, e.heure, e.artiste_norm, e.first_seen,
                     bool_and(o.source = 'eventim') AS eventim_seul
              FROM evenements e JOIN observations o ON o.evenement_id = e.id
              WHERE e.date >= CURRENT_DATE AND e.salle_id IS NOT NULL AND e.heure IS NOT NULL
                AND e.artiste_id IS NULL
              GROUP BY e.id)
            SELECT CASE WHEN e1.first_seen >= e2.first_seen THEN e1.id ELSE e2.id END AS recent,
                   CASE WHEN e1.first_seen >= e2.first_seen THEN e2.id ELSE e1.id END AS jumeau,
                   e1.salle_id AS salle_id, e1.artiste_norm AS titre_a,
                   e2.artiste_norm AS titre_b, s.nom AS salle,
                   e1.date::text AS date, e1.heure::text AS heure
            FROM ev e1
            JOIN ev e2 ON e2.salle_id = e1.salle_id AND e2.date = e1.date AND e2.heure = e1.heure
                      AND e1.id < e2.id
            JOIN salles s ON s.id = e1.salle_id
            WHERE e1.eventim_seul AND e2.eventim_seul
              AND (SELECT count(*) FROM ev x
                   WHERE x.salle_id = e1.salle_id AND x.date = e1.date AND x.heure = e1.heure) = 2
            """,
            Map.of());
    // Un comptage en SQL (fonction de fenêtre) multipliait la durée par plus de 70.
    final Map<Object, Long> parSalle =
        paires.stream()
            .collect(Collectors.groupingBy(paire -> paire.get("salle_id"), Collectors.counting()));
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    for (final Map<String, Object> paire : paires) {
      // Un théâtre à plusieurs salles joue des spectacles différents au même
      // créneau ; deux variantes d'un même programme dans une église, non.
      if (parSalle.get(paire.get("salle_id")) >= SEUIL_CRENEAUX_SALLE_MULTIPLE
          && !TitresDeSpectacle.sontVariantes(
              (String) paire.get("titre_a"), (String) paire.get("titre_b"))) {
        continue;
      }
      anomalies.put((UUID) paire.get("recent"), detailsCreneauDouble(paire));
    }
    return anomalies;
  }

  private static Map<String, Object> detailsCreneauDouble(final Map<String, Object> paire) {
    return Map.of(
        SALLE,
        paire.get(SALLE),
        DATE,
        paire.get(DATE),
        "heure",
        paire.get("heure"),
        "intitules",
        paire.get("titre_a") + " / " + paire.get("titre_b"),
        JUMEAU,
        paire.get(JUMEAU).toString());
  }
}
