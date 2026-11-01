/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker.sante;

import fr.strapontin.core.ProduitEventim;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Invariants objectifs des données, contrôlés chaque nuit et publiés dans les
 * signalements, clos d'eux-mêmes une fois corrigés. Anomalie globale ou
 * valeur texte : UUID dérivé du type ou de la valeur (reprise en détail).
 * @since 3.0.2
 * @author Kwaite
 */
final class ControleInvariantsDonnees {

  private static final String TYPE_ENTITE_GLOBAL = "invariant";
  private static final String CONFIANCE_HAUTE = "haute";
  private static final String SALLE = "salle";
  private static final String DATE = "date";
  private static final String JUMEAU = "jumeau";

  private final NamedParameterJdbcTemplate jdbc;
  private final SignalementMatchingService signalements;
  private final ControleInvariantsComplementaires complementaires;

  /* package-private : instancié par ControleIntegriteService. */
  ControleInvariantsDonnees(
      final NamedParameterJdbcTemplate jdbc, final SignalementMatchingService signalements) {
    this.jdbc = jdbc;
    this.signalements = signalements;
    this.complementaires = new ControleInvariantsComplementaires(jdbc, signalements);
  }

  /**
   * @return nombre d'anomalies constatées, tous invariants confondus
   */
  /* package-private : appelé par ControleIntegriteService. */
  int verifier() {
    return publier("orphelins_fk", TYPE_ENTITE_GLOBAL, orphelinsFk())
        + publier("sessions_sans_appareil", TYPE_ENTITE_GLOBAL, sessionsSansAppareil())
        + publier("notifications_non_envoyees", "utilisateur", notificationsNonEnvoyees())
        + publier("salle_multi_departements", SALLE, sallesMultiDepartements())
        + publier("salle_suivie_contaminee", SALLE, sallesSuiviesContaminees())
        + publier("migration_flyway_echec", TYPE_ENTITE_GLOBAL, migrationsEnEchec())
        + publier("comptes_incoherents", TYPE_ENTITE_GLOBAL, comptesIncoherents())
        + publier("identifiant_externe_duplique", "identifiant", identifiantsDupliques())
        + publier(
            "concert_double_salles_distinctes", "evenement", concertsDoublesSallesDistinctes())
        + publier("produit_eventim_double", "evenement", produitsEventimDoubles())
        + publier("fiches_figees", TYPE_ENTITE_GLOBAL, fichesFigees())
        + publier("salle_sans_ville", SALLE, sallesSansVille())
        + publier("artistes_meme_nom_sans_espaces", "artiste", artistesMemeNomSansEspaces())
        + complementaires.verifier();
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

  private Map<UUID, Map<String, Object>> orphelinsFk() {
    final Map<String, Object> comptes = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT 'evenements.salle_id' AS cle, count(*) AS nb FROM evenements e
          WHERE e.salle_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM salles s WHERE s.id = e.salle_id)
        UNION ALL
        SELECT 'evenements.artiste_id', count(*) FROM evenements e
          WHERE e.artiste_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM artistes a WHERE a.id = e.artiste_id)
        UNION ALL
        SELECT 'user_artistes.artiste_id', count(*) FROM user_artistes ua
          WHERE NOT EXISTS (SELECT 1 FROM artistes a WHERE a.id = ua.artiste_id)
        UNION ALL
        SELECT 'user_salles.salle_id', count(*) FROM user_salles us
          WHERE NOT EXISTS (SELECT 1 FROM salles s WHERE s.id = us.salle_id)
        UNION ALL
        SELECT 'user_matches.concert_id', count(*) FROM user_matches um
          WHERE NOT EXISTS (SELECT 1 FROM evenements e WHERE e.id = um.concert_id)
        """,
        Map.of(),
        rs -> {
          if (rs.getLong("nb") > 0) {
            comptes.put(rs.getString("cle"), rs.getLong("nb"));
          }
        });
    return globale("orphelins_fk", comptes);
  }

  // L'appareil n'est enregistré qu'au premier appel API après connexion,
  // d'où le délai d'une heure.
  private Map<UUID, Map<String, Object>> sessionsSansAppareil() {
    final List<String> comptes =
        jdbc.queryForList(
            """
            SELECT DISTINCT s.principal_name FROM spring_session s
            WHERE s.principal_name IS NOT NULL
              AND s.creation_time < (extract(epoch FROM now() - interval '1 hour') * 1000)
              AND NOT EXISTS (SELECT 1 FROM session_appareils sa WHERE sa.session_id = s.session_id)
            ORDER BY 1 LIMIT 20
            """,
            Map.of(),
            String.class);
    return comptes.isEmpty()
        ? Map.of()
        : globale("sessions_sans_appareil", Map.of("comptes", String.join(", ", comptes)));
  }

  private Map<UUID, Map<String, Object>> concertsDoublesSallesDistinctes() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT CASE WHEN e1.first_seen >= e2.first_seen THEN e1.id ELSE e2.id END AS recent,
               CASE WHEN e1.first_seen >= e2.first_seen THEN e2.id ELSE e1.id END AS jumeau,
               e1.artiste_norm, e1.date::text AS date,
               sa.nom AS salle_a, sb.nom AS salle_b
        FROM salles_paires_distinctes d
        JOIN evenements e1 ON e1.salle_id = d.salle_a
        JOIN evenements e2 ON e2.salle_id = d.salle_b
         AND e2.artiste_norm = e1.artiste_norm AND e2.date = e1.date
         AND e2.heure IS NOT DISTINCT FROM e1.heure
        JOIN salles sa ON sa.id = d.salle_a
        JOIN salles sb ON sb.id = d.salle_b
        WHERE e1.date >= CURRENT_DATE
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
                  JUMEAU,
                  rs.getObject(JUMEAU, UUID.class).toString()));
        });
    return anomalies;
  }

  // Salle à concerts à venir restée sans commune après la complétion nocturne : ses
  // concerts s'affichent sans ville.
  private Map<UUID, Map<String, Object>> sallesSansVille() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT s.id, s.nom, s.dept, count(*) AS nb
        FROM salles s JOIN evenements e ON e.salle_id = s.id AND e.date >= CURRENT_DATE
        WHERE s.ville IS NULL OR s.ville = ''
        GROUP BY s.id, s.nom, s.dept
        """,
        Map.of(),
        rs -> {
          final Map<String, Object> details = new LinkedHashMap<>();
          details.put(SALLE, rs.getString("nom"));
          details.put("departement", rs.getString("dept"));
          details.put("concerts", rs.getLong("nb"));
          anomalies.put(rs.getObject("id", UUID.class), details);
        });
    return anomalies;
  }

  // Fiche à venir du périmètre d'un utilisateur (département, artiste, salle ou
  // correspondance) dont aucune observation n'a été revue depuis deux semaines et
  // dont aucune absence n'est comptée : aucun fetch ne la voit.
  private Map<UUID, Map<String, Object>> fichesFigees() {
    final Map<String, Object> details = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT count(*) AS nb, min(e.date)::text AS premiere,
               string_agg(DISTINCT e.dept, ', ' ORDER BY e.dept) AS depts,
               count(*) FILTER (WHERE e.date > COALESCE(
                 (SELECT max(x.date) FROM evenements x
                  JOIN observations ox ON ox.evenement_id = x.id
                  WHERE x.salle_id = e.salle_id AND ox.updated_at > now() - interval '3 days'),
                 CURRENT_DATE)) AS hors_horizon
        FROM evenements e
        WHERE e.date >= CURRENT_DATE AND e.suppression_suggeree_le IS NULL
          AND e.absences_consecutives = 0
          AND EXISTS (SELECT 1 FROM observations o WHERE o.evenement_id = e.id)
          AND NOT EXISTS (SELECT 1 FROM observations o
                          WHERE o.evenement_id = e.id AND o.updated_at > now() - interval '14 days')
          AND (EXISTS (SELECT 1 FROM user_config uc WHERE e.dept = ANY(uc.departements))
               OR EXISTS (SELECT 1 FROM user_artistes ua WHERE ua.artiste_id = e.artiste_id)
               OR EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = e.salle_id)
               OR EXISTS (SELECT 1 FROM user_matches um WHERE um.concert_id = e.id))
        """,
        Map.of(),
        rs -> {
          if (rs.getLong("nb") > 0) {
            details.put("fiches", rs.getLong("nb"));
            details.put("premiere", rs.getString("premiere"));
            details.put("departements", rs.getString("depts"));
            // Plus tardives que toute fiche revue de leur salle : au-delà de l'horizon des
            // récupérations plutôt que retirées de la billetterie.
            details.put("horsHorizon", rs.getLong("hors_horizon"));
          }
        });
    return globale("fiches_figees", details);
  }

  // Produit Eventim sur plusieurs fiches du même jour, salle et d'heures voisines
  // après la fusion : artistes différents ou plus de deux fiches. Deux séances
  // d'un produit (matinée, soirée) ne comptent pas.
  private Map<UUID, Map<String, Object>> produitsEventimDoubles() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    final String produit = ProduitEventim.expressionSql("o");
    jdbc.query(
        "WITH p AS (SELECT DISTINCT e.id, e.salle_id, e.date, e.heure, e.first_seen, "
            + produit
            + " AS produit FROM observations o JOIN evenements e ON e.id = o.evenement_id"
            + " WHERE o.source = 'eventim' AND e.date >= CURRENT_DATE"
            + " AND e.salle_id IS NOT NULL AND "
            + produit
            + " IS NOT NULL)"
            + " SELECT CASE WHEN a.first_seen >= b.first_seen THEN a.id ELSE b.id END AS recent,"
            + " CASE WHEN a.first_seen >= b.first_seen THEN b.id ELSE a.id END AS jumeau,"
            + " a.produit AS produit, s.nom AS salle, a.date::text AS date"
            + " FROM p a JOIN p b ON b.produit = a.produit AND b.date = a.date"
            + " AND b.salle_id = a.salle_id AND a.id < b.id JOIN salles s ON s.id = a.salle_id"
            + " WHERE a.heure IS NULL OR b.heure IS NULL"
            + " OR abs(extract(epoch FROM (a.heure - b.heure))) <= 3600",
        Map.of(),
        rs -> {
          anomalies.put(
              rs.getObject("recent", UUID.class),
              Map.of(
                  SALLE,
                  rs.getString(SALLE),
                  DATE,
                  rs.getString(DATE),
                  "produit",
                  rs.getString("produit"),
                  JUMEAU,
                  rs.getObject(JUMEAU, UUID.class).toString()));
        });
    return anomalies;
  }

  // Deux artistes dont le nom ne diffère que par les espaces (« Krash Karma » /
  // « Krashkarma ») : doublon probable, créé quand une variante a été suivie
  // sans retrouver l'artiste existant. Détecté seulement : deux artistes
  // distincts peuvent s'écrire ainsi, un admin tranche. Une paire déjà examinée
  // (signalement clos) n'est plus signalée.
  private Map<UUID, Map<String, Object>> artistesMemeNomSansEspaces() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT a.id AS id, a.nom AS nom, b.nom AS autre
        FROM artistes a
        JOIN artistes b ON a.id < b.id AND replace(a.nom_norm, ' ', '') = replace(b.nom_norm, ' ', '')
        WHERE NOT EXISTS (
          SELECT 1 FROM signalements_matching sm
          WHERE sm.type = 'artistes_meme_nom_sans_espaces' AND sm.entite_id = a.id
            AND sm.resolu_le IS NOT NULL)
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              rs.getObject("id", UUID.class),
              Map.of("nom", rs.getString("nom"), "autreArtiste", rs.getString("autre")));
        });
    return anomalies;
  }

  // Match éligible (préférence active, suivi) jamais notifié un jour après
  // l'apparition du concert : digest bloqué pour cet utilisateur.
  // Seuls les comptes joignables sont signalés : un compte sans canal relève de
  // l'utilisateur, déjà averti par email et par le bandeau de l'application.
  // Le délai part du rapprochement du concert avec le compte, pas de sa
  // première détection, qui peut précéder de loin le suivi.
  private Map<UUID, Map<String, Object>> notificationsNonEnvoyees() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT um.user_id, count(*) AS nb,
               min(greatest(um.cree_le, c.first_seen))::date AS plus_ancien,
               bool_or(uc.notif_artistes OR uc.notif_salles) AS email_actif
        FROM user_matches um
        JOIN concerts c ON c.id = um.concert_id
        JOIN user_config uc ON uc.user_id = um.user_id
        WHERE um.notification_envoyee_le IS NULL AND um.masque_le IS NULL
          AND greatest(um.cree_le, c.first_seen) < now() - CASE uc.frequence_notifications
                WHEN 'hebdomadaire' THEN interval '8 days' ELSE interval '1 day' END
          AND uc.frequence_notifications <> 'jamais'
          AND ((uc.notif_artistes OR uc.notif_salles)
            OR ((uc.notif_artistes_push OR uc.notif_salles_push)
              AND EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = um.user_id)))
          AND (
            ((uc.notif_artistes OR uc.notif_artistes_push) AND EXISTS (
              SELECT 1 FROM user_artistes ua
              WHERE ua.user_id = um.user_id AND (c.artiste_id = ua.artiste_id OR EXISTS (SELECT 1 FROM evenement_artistes ea WHERE ea.evenement_id = c.id AND ea.artiste_id = ua.artiste_id))))
            OR ((uc.notif_salles OR uc.notif_salles_push) AND EXISTS (
              SELECT 1 FROM user_salles us
              WHERE us.user_id = um.user_id AND us.salle_id = c.salle_id)))
        GROUP BY um.user_id
        """,
        Map.of(),
        rs -> {
          final Map<String, Object> details = new LinkedHashMap<>();
          details.put(
              "cause",
              rs.getBoolean("email_actif")
                  ? "Envoi en échec alors que l'email est actif."
                  : "Envoi en échec alors qu'un appareil est abonné au push.");
          details.put("action", "Vérifier dans les journaux l'envoi des emails et des push.");
          details.put("concerts", rs.getLong("nb"));
          details.put("plusAncien", rs.getObject("plus_ancien", LocalDate.class).toString());
          details.put("userId", rs.getString("user_id"));
          anomalies.put(uuidDe("utilisateur:" + rs.getString("user_id")), details);
        });
    return anomalies;
  }

  // Événements d'une salle sur plusieurs départements : une résolution floue
  // erronée rattache directement, sans second mapping.
  private Map<UUID, Map<String, Object>> sallesMultiDepartements() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT salle_id, string_agg(DISTINCT dept, ', ' ORDER BY dept) AS depts
        FROM evenements
        WHERE salle_id IS NOT NULL AND dept IS NOT NULL
        GROUP BY salle_id
        HAVING count(DISTINCT dept) > 1
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              rs.getObject("salle_id", UUID.class), Map.of("departements", rs.getString("depts")));
        });
    return anomalies;
  }

  // Salle suivie déjà reconnue contaminée (départements multiples,
  // contamination ou dispersion) : impact utilisateur direct.
  private Map<UUID, Map<String, Object>> sallesSuiviesContaminees() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT us.salle_id, count(DISTINCT us.user_id) AS nb_utilisateurs
        FROM user_salles us JOIN salles s ON s.id = us.salle_id
        WHERE s.contamination_signalee IS NOT NULL
           OR EXISTS (SELECT 1 FROM signalements_matching sm
                      WHERE sm.entite_id = s.id AND sm.resolu_le IS NULL
                        AND sm.type IN ('dispersion_sources_salle', 'salle_multi_departements'))
        GROUP BY us.salle_id
        """,
        Map.of(),
        rs -> {
          anomalies.put(
              rs.getObject("salle_id", UUID.class),
              Map.of("nbUtilisateurs", rs.getLong("nb_utilisateurs")));
        });
    return anomalies;
  }

  private Map<UUID, Map<String, Object>> migrationsEnEchec() {
    final List<String> versions =
        jdbc.queryForList(
            "SELECT coalesce(version, '') || ' ' || description FROM flyway_schema_history"
                + " WHERE NOT success ORDER BY installed_rank",
            Map.of(),
            String.class);
    return versions.isEmpty()
        ? Map.of()
        : globale("migration_flyway_echec", Map.of("migrations", String.join(", ", versions)));
  }

  // Compte activé : un seul identifiant de connexion, mot de passe bcrypt.
  private Map<UUID, Map<String, Object>> comptesIncoherents() {
    final Map<String, Object> comptes = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT 'sansIdentifiant' AS cle, count(*) AS nb FROM users u
          WHERE u.must_change_credentials = false AND NOT EXISTS (
            SELECT 1 FROM accounts a WHERE a.user_id = u.id AND a.provider_id = 'credential')
        UNION ALL
        SELECT 'identifiantsMultiples', count(*) FROM (
          SELECT user_id FROM accounts WHERE provider_id = 'credential'
          GROUP BY user_id HAVING count(*) > 1) d
        UNION ALL
        SELECT 'motDePasseNonBcrypt', count(*) FROM accounts
          WHERE provider_id = 'credential' AND password IS NOT NULL AND password NOT LIKE '$2%'
        """,
        Map.of(),
        rs -> {
          if (rs.getLong("nb") > 0) {
            comptes.put(rs.getString("cle"), rs.getLong("nb"));
          }
        });
    return globale("comptes_incoherents", comptes);
  }

  // Un identifiant Wikidata ou Deezer désigne une seule salle ou un seul
  // artiste.
  private Map<UUID, Map<String, Object>> identifiantsDupliques() {
    final Map<UUID, Map<String, Object>> anomalies = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT 'salle wikidata' AS nature, wikidata_id AS valeur, count(*) AS nb FROM salles
          WHERE wikidata_id IS NOT NULL GROUP BY wikidata_id HAVING count(*) > 1
        UNION ALL
        SELECT 'artiste wikidata', wikidata_id, count(*) FROM artistes
          WHERE wikidata_id IS NOT NULL GROUP BY wikidata_id HAVING count(*) > 1
        UNION ALL
        SELECT 'artiste deezer', deezer_id, count(*) FROM artistes
          WHERE deezer_id IS NOT NULL GROUP BY deezer_id HAVING count(*) > 1
        """,
        Map.of(),
        rs -> {
          final String nature = rs.getString("nature");
          final String valeur = rs.getString("valeur");
          anomalies.put(
              uuidDe(nature + ":" + valeur),
              Map.of("nature", nature, "valeur", valeur, "nb", rs.getLong("nb")));
        });
    return anomalies;
  }
}
