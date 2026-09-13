/*
 * Strapontin — concert finder
 * Copyright (C) 2025-2026 Kwaite
 */
package fr.strapontin.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Chargement et regroupement par utilisateur des {@code user_matches} non notifiés, prêts pour
 * l'envoi du digest.
 *
 * <p>Extrait de {@link NotificationDigestService} pour rester sous la limite de 500 lignes par
 * fichier.
 *
 * @since 2.9.1
 * @author Kwaite
 */
class MatchesNonNotifiesLoader {

  private static final String SQL_MATCHES_NON_NOTIFIES =
      """
      SELECT um.user_id, u.email, CAST(c.id AS text) AS concert_id,
             COALESCE(a.nom, c.artiste) AS artiste,
             CAST(c.date AS text) AS date, c.salle, c.ville, c.url, c.artiste_norm,
             c.statut,
             uc.notif_artistes, uc.notif_salles,
             uc.notif_artistes_push, uc.notif_salles_push,
             uc.frequence_notifications::text AS frequence_notifications,
             CASE
               WHEN EXISTS (
                 SELECT 1 FROM user_artistes ua
                 WHERE ua.user_id = um.user_id
                   AND c.artiste_id = ua.artiste_id
               ) THEN 'ARTISTE'
               ELSE 'SALLE'
             END AS source
      FROM user_matches um
      JOIN concerts    c  ON c.id      = um.concert_id
      LEFT JOIN artistes a ON a.id = c.artiste_id
      JOIN users       u  ON u.id      = um.user_id
      JOIN user_config uc ON uc.user_id = um.user_id
      WHERE um.notification_envoyee_le IS NULL
        AND um.masque_le  IS NULL
        AND (
          (uc.frequence_notifications = CAST(:frequence AS frequence_notif)
           AND (
             ((uc.notif_artistes = true OR uc.notif_artistes_push = true) AND EXISTS (
               SELECT 1 FROM user_artistes ua
               WHERE ua.user_id = um.user_id
                 AND c.artiste_id = ua.artiste_id
             ))
             OR
             ((uc.notif_salles = true OR uc.notif_salles_push = true) AND EXISTS (
               SELECT 1 FROM user_salles us
               WHERE us.user_id = um.user_id
                 AND c.salle_id = us.salle_id
             ))
           ))
          OR
          (CAST(:frequence AS frequence_notif) = 'temps_reel'
           AND uc.frequence_notifications = 'jamais'
           AND (
             (uc.notif_artistes_push = true AND EXISTS (
               SELECT 1 FROM user_artistes ua
               WHERE ua.user_id = um.user_id
                 AND c.artiste_id = ua.artiste_id
             ))
             OR
             (uc.notif_salles_push = true AND EXISTS (
               SELECT 1 FROM user_salles us
               WHERE us.user_id = um.user_id
                 AND c.salle_id = us.salle_id
             ))
           ))
        )
      ORDER BY um.user_id, c.date
      """;

  private final NamedParameterJdbcTemplate jdbc;

  /* package-private : instancié uniquement par NotificationDigestService (même
   * package). */
  MatchesNonNotifiesLoader(final NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Matches non notifiés regroupés par utilisateur, prêts pour l'envoi du digest.
   *
   * @param emailsUtilisateurs email de chaque utilisateur concerné
   * @param configsUtilisateurs préférences de notification de chaque utilisateur
   * @param parUtilisateur concerts non notifiés, groupés par utilisateur
   */
  /* package-private : type de retour interne, utilisé par
   * NotificationDigestService (même package). */
  record MatchesGroupes(
      Map<String, String> emailsUtilisateurs,
      Map<String, NotificationDigestService.ConfigNotifUser> configsUtilisateurs,
      Map<String, List<NotificationDigestService.ConcertNotif>> parUtilisateur) {}

  /**
   * @param frequence {@code "temps_reel"} ou {@code "quotidien"}
   * @return les matches non notifiés, groupés par utilisateur
   */
  /* package-private : appelé par NotificationDigestService (même package). */
  MatchesGroupes charger(final String frequence) {
    final List<Map<String, Object>> lignes =
        jdbc.queryForList(SQL_MATCHES_NON_NOTIFIES, Map.of("frequence", frequence));
    return regrouperLignesParUtilisateur(lignes);
  }

  // ConfigNotifUser/ConcertNotif/ArrayList portent les champs de la ligne
  // courante — pas hissables hors boucle.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private MatchesGroupes regrouperLignesParUtilisateur(final List<Map<String, Object>> lignes) {
    final Map<String, String> emailsUtilisateurs = new LinkedHashMap<>();
    final Map<String, NotificationDigestService.ConfigNotifUser> configsUtilisateurs =
        new LinkedHashMap<>();
    final Map<String, List<NotificationDigestService.ConcertNotif>> parUtilisateur =
        new LinkedHashMap<>();
    for (final Map<String, Object> ligne : lignes) {
      final String userId = (String) ligne.get("user_id");
      emailsUtilisateurs.putIfAbsent(userId, (String) ligne.get("email"));
      configsUtilisateurs.putIfAbsent(
          userId,
          new NotificationDigestService.ConfigNotifUser(
              Boolean.TRUE.equals(ligne.get("notif_artistes")),
              Boolean.TRUE.equals(ligne.get("notif_salles")),
              Boolean.TRUE.equals(ligne.get("notif_artistes_push")),
              Boolean.TRUE.equals(ligne.get("notif_salles_push")),
              (String) ligne.get("frequence_notifications")));
      parUtilisateur
          .computeIfAbsent(userId, _ -> new ArrayList<>())
          .add(
              new NotificationDigestService.ConcertNotif(
                  (String) ligne.get("artiste"),
                  (String) ligne.get("date"),
                  (String) ligne.get("salle"),
                  (String) ligne.get("ville"),
                  (String) ligne.get("url"),
                  (String) ligne.get("concert_id"),
                  (String) ligne.get("artiste_norm"),
                  (String) ligne.get("source"),
                  (String) ligne.get("statut")));
    }
    return new MatchesGroupes(emailsUtilisateurs, configsUtilisateurs, parUtilisateur);
  }
}
