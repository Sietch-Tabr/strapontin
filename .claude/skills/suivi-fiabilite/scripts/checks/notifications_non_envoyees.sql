-- Correctness des notifications (pas seulement leur volume) : user_matches
-- réellement éligibles à une notification (préférence active email ou push,
-- artiste ou salle suivi) mais jamais notifiés, alors que le concert est
-- connu depuis plus d'un jour. NotificationDigestService.executerDigest
-- tourne juste après chaque recalcul (fetch:artistes:batch -> recalculer:
-- matches -> notifier:digest temps_reel) : un match encore non notifié
-- après 24h signale un digest resté silencieusement bloqué pour cet
-- utilisateur (quota Resend, clé API absente, VAPID mal configuré...), pas
-- une simple absence d'activité.
-- Les comptes en fréquence hebdomadaire ne sont comptés qu'une fois passé le
-- récapitulatif du dimanche 12h (Paris) suivant la détection du concert : avant,
-- le match en attente est normal (faux positif du 07/11/2026, thomasw).
SELECT um.user_id, c.artiste, c.date, c.first_seen,
  CASE WHEN EXISTS (
    SELECT 1 FROM user_artistes ua WHERE ua.user_id = um.user_id AND ua.artiste_id = c.artiste_id
  ) THEN 'ARTISTE' ELSE 'SALLE' END AS source
FROM user_matches um
JOIN concerts c ON c.id = um.concert_id
JOIN user_config uc ON uc.user_id = um.user_id
WHERE um.notification_envoyee_le IS NULL
  AND um.masque_le IS NULL
  AND c.first_seen < now() - interval '1 day'
  AND (uc.frequence_notifications <> 'hebdomadaire'
       OR c.first_seen AT TIME ZONE 'Europe/Paris' < (
         SELECT CASE WHEN z.t < z.d + interval '12 hours' THEN z.d - 7 ELSE z.d END + interval '12 hours'
         FROM (SELECT now() AT TIME ZONE 'Europe/Paris' AS t,
                      (now() AT TIME ZONE 'Europe/Paris')::date
                        - extract(dow FROM now() AT TIME ZONE 'Europe/Paris')::int AS d) z))
  AND (
    (uc.frequence_notifications <> 'jamais' AND (
      ((uc.notif_artistes OR uc.notif_artistes_push) AND EXISTS (
        SELECT 1 FROM user_artistes ua WHERE ua.user_id = um.user_id AND ua.artiste_id = c.artiste_id))
      OR ((uc.notif_salles OR uc.notif_salles_push) AND EXISTS (
        SELECT 1 FROM user_salles us WHERE us.user_id = um.user_id AND us.salle_id = c.salle_id))
    ))
    OR (uc.frequence_notifications = 'jamais' AND (
      (uc.notif_artistes_push AND EXISTS (
        SELECT 1 FROM user_artistes ua WHERE ua.user_id = um.user_id AND ua.artiste_id = c.artiste_id))
      OR (uc.notif_salles_push AND EXISTS (
        SELECT 1 FROM user_salles us WHERE us.user_id = um.user_id AND us.salle_id = c.salle_id))
    ))
  )
ORDER BY c.first_seen;
