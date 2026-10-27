/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.EchecPush;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Invariants de données complémentaires : secrets TOTP, envois push,
 * annulations, hommages et séances déclarées dans deux salles.
 * @since 3.1.1
 * @author Kwaite
 */
final class ControleInvariantsComplementaires {

  private static final String TYPE_ENTITE_GLOBAL = "invariant";
  private static final String CONFIANCE_HAUTE = "haute";
  private static final String EVENEMENT = "evenement";
  private static final String DATE = "date";
  private static final String JUMEAU = "jumeau";

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;

  /* package-private : instancié par ControleInvariantsDonnees. */
  ControleInvariantsComplementaires(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
  }

  /**
   * @return nombre d'anomalies constatées, tous ces invariants confondus
   */
  /* package-private : appelé par ControleInvariantsDonnees. */
  int verifier() {
    return publier("hommage_rattache_artiste", EVENEMENT, hommagesRattachesAUnArtiste())
        + publier("annulation_une_billetterie", EVENEMENT, annulationsUneBilletterie())
        + publier("secret_totp_non_chiffre", "utilisateur", secretsTotpNonChiffres())
        + publier("envois_push_en_panne", TYPE_ENTITE_GLOBAL, envoisPushEnPanne())
        + publier("seance_dans_deux_salles", EVENEMENT, seancesDansDeuxSalles());
  }

  private int publier(
      final String type, final String entiteType, final Map<UUID, Map<String, Object>> anomalies) {
    anomalies.forEach(
        (entite, details) ->
            signalements.signaler(type, entiteType, entite, CONFIANCE_HAUTE, details));
    signalements.clore(type, anomalies.keySet());
    return anomalies.size();
  }

  private static UUID uuidDe(final String valeur) {
    return UUID.nameUUIDFromBytes(valeur.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<UUID, Map<String, Object>> globale(
      final String type, final Map<String, Object> details) {
    return details.isEmpty() ? Map.of() : Map.of(uuidDe("invariant:" + type), details);
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
    return globale("envois_push_en_panne", services);
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
          anomalies.put(uuidDe(rs.getString("id")), Map.of("utilisateurId", rs.getString("id")));
        });
    return anomalies;
  }

  // Même concert sur deux salles déclarées distinctes (admin ou répartition) :
  // un lieu resté sur la mauvaise salle l'a recréé. Le plus récent est signalé.
  // Même artiste, même date, heures à moins de trois heures : dans une salle et
  // sa sous-salle,
  // dans deux salles d'une même commune (séance déplacée restée publiée sous
  // l'ancienne) ou
  // dans une même salle sous deux heures (deux billetteries). Les paires
  // déclarées distinctes
  // relèvent de concert_double_salles_distinctes.
  private Map<UUID, Map<String, Object>> seancesDansDeuxSalles() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT CASE WHEN e1.first_seen >= e2.first_seen THEN e1.id ELSE e2.id END AS recent,
               CASE WHEN e1.first_seen >= e2.first_seen THEN e2.id ELSE e1.id END AS jumeau,
               e1.artiste_norm, e1.date::text AS date,
               coalesce(sa.nom, '?') AS salle_a, coalesce(sb.nom, '?') AS salle_b,
               e1.heure::text AS heure_a, e2.heure::text AS heure_b
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
               OR lower(sa.ville) = lower(sb.ville))
          AND NOT (sa.id = sb.id AND e1.heure = e2.heure)
          AND NOT EXISTS (
            SELECT 1 FROM salles_paires_distinctes d
            WHERE d.salle_a = LEAST(sa.id, sb.id) AND d.salle_b = GREATEST(sa.id, sb.id))
          AND EXISTS (SELECT 1 FROM observations o
                      WHERE o.evenement_id IN (e1.id, e2.id)
                        AND o.updated_at > now() - interval '7 days')
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              rs.getObject("recent", UUID.class),
              Map.of(
                  "artiste",
                  rs.getString("artiste_norm"),
                  DATE,
                  rs.getString(DATE),
                  "salles",
                  rs.getString("salle_a") + " / " + rs.getString("salle_b"),
                  "heures",
                  rs.getString("heure_a") + " / " + rs.getString("heure_b"),
                  JUMEAU,
                  rs.getObject(JUMEAU, UUID.class).toString()));
        });
    return anomalies;
  }
}
