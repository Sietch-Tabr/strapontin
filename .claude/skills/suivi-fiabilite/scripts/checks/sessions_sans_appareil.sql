-- Sessions authentifiées (principal_name renseigné = login réel) sans ligne
-- session_appareils correspondante. AppareilEnregistrementInterceptor
-- n'enregistre l'appareil qu'au premier appel API suivant le login (Spring
-- Session ne persiste la session qu'après la réponse du login lui-même) —
-- un raté de cet enregistrement (appel API jamais suivi, erreur de
-- l'intercepteur) laisserait une session authentifiée sans appareil
-- traçable, invisible aux pages "Sessions actives" de /parametres et /admin.
SELECT s.session_id, s.principal_name,
  to_timestamp(s.creation_time / 1000) AS creation_time,
  to_timestamp(s.last_access_time / 1000) AS last_access_time
FROM spring_session s
WHERE s.principal_name IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM session_appareils sa WHERE sa.session_id = s.session_id
  )
ORDER BY s.creation_time DESC;
