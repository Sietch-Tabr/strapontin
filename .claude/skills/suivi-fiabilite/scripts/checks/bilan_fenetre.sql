-- Bilan de la fenêtre du check en un appel : à lancer avec
--   python3 scripts/run_check.py bilan_fenetre --borne "2026-11-02 21:34"
-- (heure de Paris, __BORNE__ est remplacé avant l'envoi). Chaque section est agrégée :
-- le détail d'une ligne suspecte se creuse ensuite à la main.
\echo ---TAILLE_BASE
SELECT pg_size_pretty(pg_database_size('strapontin')) AS base,
       (SELECT count(*) FROM flyway_schema_history) AS migrations,
       (SELECT count(*) FROM flyway_schema_history WHERE success = false) AS migrations_en_echec;
\echo ---JOBS
SELECT type_job, derniere_exec::timestamptz(0) AS derniere_exec, duree_ms, nb_inseres, nb_mis_a_jour, erreur
FROM fetch_jobs ORDER BY type_job;
\echo ---INSERTIONS_PAR_HEURE_ET_SOURCE
SELECT date_trunc('hour', first_seen)::timestamptz(0) AS heure, source, count(*) AS nombre
FROM concerts WHERE first_seen > '__BORNE__ Europe/Paris'::timestamptz GROUP BY 1, 2 ORDER BY 1, 2;
\echo ---INSERTIONS_PAR_SALLE_TOP5
SELECT s.nom, s.ville, count(*) AS nombre
FROM concerts c JOIN salles s ON s.id = c.salle_id
WHERE c.first_seen > '__BORNE__ Europe/Paris'::timestamptz
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 5;
\echo ---FICHES_A_VENIR
SELECT count(*) AS futures, count(*) FILTER (WHERE s.dept = '75') AS dept_75
FROM evenements e LEFT JOIN salles s ON s.id = e.salle_id WHERE e.date >= CURRENT_DATE;
\echo ---LOGS_ADMIN_WARN_ERROR
SELECT ts::timestamptz(0) AS ts, niveau, source, left(message, 220) AS message
FROM logs_admin
WHERE niveau IN ('warn', 'error') AND ts > '__BORNE__ Europe/Paris'::timestamptz
  AND message NOT LIKE 'Accès API non authentifié%'
ORDER BY ts;
SELECT count(*) AS acces_banniere_non_authentifies
FROM logs_admin WHERE message LIKE 'Accès API non authentifié%' AND ts > '__BORNE__ Europe/Paris'::timestamptz;
\echo ---FUSIONS_ET_ALIGNEMENTS
SELECT ts::timestamptz(0) AS ts, left(message, 200) AS message
FROM logs_admin
WHERE ts > '__BORNE__ Europe/Paris'::timestamptz
  AND (message ILIKE '%fusionn%' OR message ILIKE 'Ville de la salle%' OR message ILIKE 'fusion salle%' OR message ILIKE '%supprimé définitivement%' OR message ILIKE 'flag de suppression annulé%')
ORDER BY ts LIMIT 40;
\echo ---SIGNALEMENTS_ACTIFS_PAR_TYPE
SELECT type, count(*) AS actifs FROM signalements_matching WHERE resolu_le IS NULL GROUP BY 1 ORDER BY 2 DESC;
SELECT type, count(*) AS detectes_dans_la_fenetre FROM signalements_matching
WHERE detecte_le > '__BORNE__ Europe/Paris'::timestamptz GROUP BY 1 ORDER BY 2 DESC;
\echo ---SUSPECTS
SELECT 'artiste_sources' AS table_, count(*) FILTER (WHERE suspect_depuis IS NOT NULL) AS suspects,
       count(*) FILTER (WHERE necessite_intervention_le IS NOT NULL) AS intervention FROM artiste_sources;
SELECT s.nom, ss.source::text, ss.suspect_depuis::timestamptz(0) AS suspect_depuis
FROM salle_sources ss JOIN salles s ON s.id = ss.salle_id WHERE ss.suspect_depuis IS NOT NULL ORDER BY 1;
\echo ---ABSENCES
SELECT CASE WHEN absences_consecutives = 0 THEN '0' WHEN absences_consecutives < 5 THEN '1-4'
            WHEN absences_consecutives < 20 THEN '5-19' WHEN absences_consecutives < 50 THEN '20-49' ELSE '50+' END AS tranche,
       count(*) AS fiches
FROM evenements WHERE date >= CURRENT_DATE GROUP BY 1 ORDER BY 1;
SELECT count(*) FILTER (WHERE maj < now() - interval '14 days') AS figees, count(*) AS total
FROM (SELECT e.id, max(o.updated_at) AS maj FROM evenements e JOIN observations o ON o.evenement_id = e.id
      WHERE e.date >= CURRENT_DATE GROUP BY e.id) x;
\echo ---FILE_A_CONFIRMER
SELECT count(*) FILTER (WHERE date >= CURRENT_DATE) AS a_venir, count(*) AS total
FROM evenements WHERE suppression_suggeree_le IS NOT NULL;
\echo ---NOTIFICATIONS
SELECT ts::timestamptz(0) AS ts, left(message, 140) AS message FROM logs_admin
WHERE message ILIKE 'digest%' AND message NOT LIKE 'digest — 0 email(s), 0 push envoy%'
  AND ts > '__BORNE__ Europe/Paris'::timestamptz ORDER BY ts;
SELECT count(*) AS digests_a_zero FROM logs_admin
WHERE message LIKE 'digest — 0 email(s), 0 push envoy%' AND ts > '__BORNE__ Europe/Paris'::timestamptz;
SELECT u.display_username, left(u.id::text, 8) AS id8, c.artiste, c.salle, c.date, um.notification_envoyee_le::timestamptz(0) AS envoyee_le
FROM user_matches um JOIN users u ON u.id = um.user_id JOIN concerts c ON c.id = um.concert_id
WHERE um.notification_envoyee_le > '__BORNE__ Europe/Paris'::timestamptz ORDER BY um.notification_envoyee_le;
SELECT left(user_id::text, 8) AS id8, version, envoyee_le::timestamptz(0) AS envoyee_le
FROM notifications_version ORDER BY envoyee_le DESC LIMIT 3;
\echo ---UTILISATEURS_ET_SESSIONS
SELECT (SELECT count(*) FROM users) AS comptes,
       (SELECT count(*) FROM spring_session) AS sessions,
       (SELECT count(*) FROM spring_session WHERE expiry_time < extract(epoch from now()) * 1000) AS expirees,
       (SELECT count(*) FROM push_abonnements) AS abonnements_push;
SELECT s.principal_name, to_timestamp(s.creation_time / 1000)::timestamptz(0) AS cree,
       to_timestamp(s.last_access_time / 1000)::timestamptz(0) AS dernier_acces
FROM spring_session s
WHERE s.principal_name IS NOT NULL AND s.last_access_time > extract(epoch from '__BORNE__ Europe/Paris'::timestamptz) * 1000
ORDER BY 3;
