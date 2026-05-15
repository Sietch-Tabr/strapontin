-- migrate_data.sql — Migration des données Strapontin v1 → v2
--
-- Exécuter depuis le LXC DESTINATION (vert / strapontin_v2) :
--
--   Prod (LXC 115 → lit depuis LXC 113) :
--     psql -U postgres -d strapontin \
--          -v source_conn="host=192.168.20.79 dbname=strapontin user=strapontin password=xxx" \
--          -f migrate_data.sql
--
--   Dev (même LXC 114, nouvelle DB strapontin_v2 → lit depuis strapontin) :
--     psql -U postgres -d strapontin_v2 \
--          -v source_conn="host=localhost dbname=strapontin user=strapontin password=xxx" \
--          -f migrate_data.sql
--
-- Prérequis :
--   1. DB cible vide avec Flyway V1-V4 appliquées (tables créées)
--   2. Extension dblink disponible sur le LXC destination
--   3. PostgreSQL source accessible (arrêter Node.js avant, pas PostgreSQL)
--   4. Même APP_CONFIG_SECRET sur source et destination (app_config copiée telle quelle)
--
-- Ce qui N'est PAS migré (intentionnel) :
--   - sessions      : tous les utilisateurs se reconnectent après bascule
--   - two_factor    : TOTP re-configuré manuellement (Spring Security natif, pas Better-Auth)
--   - verifications : tokens de reset expirés/invalides dans le nouveau contexte
--   - fetch_jobs    : historique Node.js non pertinent pour Java/Quartz
--
-- Idempotent : ON CONFLICT DO NOTHING → safe à rejouer en cas d'interruption.

\set ON_ERROR_STOP on
\timing on

\echo ''
\echo '╔══════════════════════════════════════════════════════╗'
\echo '║   Migration Strapontin  v1 (Node.js) → v2 (Java)    ║'
\echo '╚══════════════════════════════════════════════════════╝'
\echo ''

BEGIN;

-- Extension dblink (nécessaire pour lire la source via TCP)
CREATE EXTENSION IF NOT EXISTS dblink;

-- ---------------------------------------------------------------
-- Comptages source (référence de validation en fin de script)
-- ---------------------------------------------------------------

\echo '[0/9] Comptages source (référence)...'

SELECT
    src.nb_users       AS "users (source)",
    src.nb_concerts    AS "concerts (source, hors bandsintown)",
    src.nb_accounts    AS "accounts credential (source)",
    src.nb_artistes    AS "user_artistes (source)",
    src.nb_config      AS "user_config (source)",
    src.nb_salles      AS "user_salles (source)",
    src.nb_matches     AS "user_matches (source)",
    src.nb_push        AS "push_abonnements (source)",
    src.nb_app_config  AS "app_config (source)"
FROM dblink(:'source_conn',
    $$SELECT
        (SELECT COUNT(*) FROM users)                                   AS nb_users,
        (SELECT COUNT(*) FROM concerts WHERE source != 'bandsintown') AS nb_concerts,
        (SELECT COUNT(*) FROM accounts WHERE provider_id = 'credential') AS nb_accounts,
        (SELECT COUNT(*) FROM user_artistes)                           AS nb_artistes,
        (SELECT COUNT(*) FROM user_config)                             AS nb_config,
        (SELECT COUNT(*) FROM user_salles)                             AS nb_salles,
        (SELECT COUNT(*) FROM user_matches)                            AS nb_matches,
        (SELECT COUNT(*) FROM push_abonnements)                        AS nb_push,
        (SELECT COUNT(*) FROM app_config)                              AS nb_app_config$$)
AS src(nb_users bigint, nb_concerts bigint, nb_accounts bigint,
       nb_artistes bigint, nb_config bigint, nb_salles bigint,
       nb_matches bigint, nb_push bigint, nb_app_config bigint);

-- ---------------------------------------------------------------
-- 1. users
--    Transformations :
--      - image supprimée (#G)
--      - role TEXT → ENUM role_utilisateur (#J)
--      - two_factor_enabled forcé à false (TOTP re-configuré post-bascule)
-- ---------------------------------------------------------------

\echo '[1/9] Utilisateurs...'

INSERT INTO users (id, name, email, email_verified, role, username, display_username,
                   two_factor_enabled, must_change_credentials, created_at, updated_at)
SELECT
    id,
    name,
    email,
    COALESCE(email_verified, false),
    role::role_utilisateur,
    username,
    display_username,
    false,                                  -- TOTP re-configuré post-bascule
    COALESCE(must_change_credentials, false),
    created_at,
    updated_at
FROM dblink(:'source_conn',
    $$SELECT id, name, email, email_verified, role, username, display_username,
             must_change_credentials, created_at, updated_at
      FROM users$$)
AS src(id text, name text, email text, email_verified boolean,
       role text, username text, display_username text,
       must_change_credentials boolean,
       created_at timestamptz, updated_at timestamptz)
ON CONFLICT (id) DO NOTHING;

SELECT COUNT(*) AS "users migrés" FROM users;

-- ---------------------------------------------------------------
-- 2. concerts
--    Transformations :
--      - source TEXT → ENUM source_concert (#J, #I : filtre bandsintown)
--      - disponible EXCLUDED (colonne GENERATED dans v2 — calculée depuis statut)
-- ---------------------------------------------------------------

\echo '[2/9] Concerts (filtre bandsintown, sans colonne disponible générée)...'

INSERT INTO concerts (id, artiste, artiste_norm, date, heure, salle, salle_norm,
                      ville, dept, prix_min, prix_max, url, source, classification,
                      genre, statut, first_seen, updated_at)
SELECT
    id,
    artiste,
    artiste_norm,
    date,
    heure,
    salle,
    salle_norm,
    ville,
    dept,
    prix_min,
    prix_max,
    url,
    source::source_concert,
    classification,
    genre,
    statut,
    first_seen,
    updated_at
FROM dblink(:'source_conn',
    $$SELECT id, artiste, artiste_norm, date, heure, salle, salle_norm, ville, dept,
             prix_min, prix_max, url, source, classification, genre, statut,
             first_seen, updated_at
      FROM concerts
      WHERE source != 'bandsintown'$$)
AS src(id uuid, artiste text, artiste_norm text, date date, heure time,
       salle text, salle_norm text, ville text, dept varchar(3),
       prix_min numeric(8,2), prix_max numeric(8,2), url text, source text,
       classification text, genre text, statut text,
       first_seen timestamptz, updated_at timestamptz)
ON CONFLICT (artiste_norm, date, salle_norm) DO NOTHING;

SELECT COUNT(*) AS "concerts migrés" FROM concerts;

-- ---------------------------------------------------------------
-- 3. salles (NOUVELLE table — correction #C)
--    Peuplée depuis les concerts migrés (salle_norm = id naturel).
--    Fallback : si user_salles référence un salle_id absent des concerts,
--    on l'insère avec salle_id comme nom (ne devrait pas arriver en pratique).
-- ---------------------------------------------------------------

\echo '[3/9] Salles (depuis concerts migrés + fallback user_salles orphelins)...'

-- Source principale : concerts (salle = nom affiché, salle_norm = clé)
-- DISTINCT ON + ORDER BY length DESC → préfère le nom le plus descriptif
INSERT INTO salles (id, nom, nom_norm)
SELECT DISTINCT ON (salle_norm)
    salle_norm,
    salle,
    salle_norm
FROM concerts
ORDER BY salle_norm, length(salle) DESC
ON CONFLICT (id) DO NOTHING;

-- Fallback : user_salles dont le salle_id n'est pas couvert par les concerts
-- (salle ajoutée mais aucun concert encore en base)
INSERT INTO salles (id, nom, nom_norm)
SELECT DISTINCT us.salle_id, us.salle_id, us.salle_id
FROM dblink(:'source_conn', $$SELECT DISTINCT salle_id FROM user_salles$$)
    AS us(salle_id text)
WHERE us.salle_id NOT IN (SELECT id FROM salles)
ON CONFLICT (id) DO NOTHING;

SELECT COUNT(*) AS "salles créées" FROM salles;

-- ---------------------------------------------------------------
-- 4. accounts
--    Transformations :
--      - colonnes OAuth supprimées (#F) : access_token, refresh_token,
--        id_token, *_expires_at, scope non copiées
--      - filtre : provider_id = 'credential' uniquement
-- ---------------------------------------------------------------

\echo '[4/9] Comptes (hash scrypt — re-hashés bcrypt au premier login)...'

INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
SELECT id, account_id, provider_id, user_id, password, created_at, updated_at
FROM dblink(:'source_conn',
    $$SELECT id, account_id, provider_id, user_id, password, created_at, updated_at
      FROM accounts
      WHERE provider_id = 'credential'
        AND user_id IN (SELECT id FROM users)$$)
AS src(id text, account_id text, provider_id text, user_id text, password text,
       created_at timestamptz, updated_at timestamptz)
WHERE src.user_id IN (SELECT id FROM users)
ON CONFLICT (id) DO NOTHING;

SELECT COUNT(*) AS "accounts migrés" FROM accounts;

-- ---------------------------------------------------------------
-- 5. push_abonnements
-- ---------------------------------------------------------------

\echo '[5/9] Abonnements push...'

INSERT INTO push_abonnements (id, user_id, endpoint, p256dh, auth, cree_le)
SELECT id, user_id, endpoint, p256dh, auth, cree_le
FROM dblink(:'source_conn',
    $$SELECT id, user_id, endpoint, p256dh, auth, cree_le FROM push_abonnements$$)
AS src(id uuid, user_id text, endpoint text, p256dh text, auth text, cree_le timestamptz)
WHERE src.user_id IN (SELECT id FROM users)
ON CONFLICT (user_id, endpoint) DO NOTHING;

SELECT COUNT(*) AS "push_abonnements migrés" FROM push_abonnements;

-- ---------------------------------------------------------------
-- 6. user_artistes
-- ---------------------------------------------------------------

\echo '[6/9] Artistes suivis...'

INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche, created_at)
SELECT user_id, artiste_normalise, artiste_affiche, created_at
FROM dblink(:'source_conn',
    $$SELECT user_id, artiste_normalise, artiste_affiche, created_at FROM user_artistes$$)
AS src(user_id text, artiste_normalise text, artiste_affiche text, created_at timestamptz)
WHERE src.user_id IN (SELECT id FROM users)
ON CONFLICT (user_id, artiste_normalise) DO NOTHING;

SELECT COUNT(*) AS "user_artistes migrés" FROM user_artistes;

-- ---------------------------------------------------------------
-- 7. user_config
--    Transformations :
--      - frequence_notifications TEXT → ENUM frequence_notif (#J)
--      - theme TEXT → ENUM theme_interface (#J)
--      Valeurs invalides remplacées par les défauts (ne devrait pas arriver
--      car les CHECK contraintes source les rejettent déjà).
-- ---------------------------------------------------------------

\echo '[7/9] Configuration utilisateurs...'

INSERT INTO user_config (user_id, departements, filtre_disponibles,
                          frequence_notifications, notif_artistes, notif_salles,
                          theme, updated_at)
SELECT
    user_id,
    COALESCE(departements, '{}'),
    COALESCE(filtre_disponibles, true),
    COALESCE(frequence_notifications, 'jamais')::frequence_notif,
    COALESCE(notif_artistes, true),
    COALESCE(notif_salles, false),
    COALESCE(theme, 'auto')::theme_interface,
    updated_at
FROM dblink(:'source_conn',
    $$SELECT user_id, departements, filtre_disponibles, frequence_notifications,
             notif_artistes, notif_salles, theme, updated_at
      FROM user_config$$)
AS src(user_id text, departements text[], filtre_disponibles boolean,
       frequence_notifications text, notif_artistes boolean,
       notif_salles boolean, theme text, updated_at timestamptz)
WHERE src.user_id IN (SELECT id FROM users)
ON CONFLICT (user_id) DO NOTHING;

SELECT COUNT(*) AS "user_config migrés" FROM user_config;

-- ---------------------------------------------------------------
-- 8. user_salles
--    Transformations :
--      - created_at ajouté (#C : ancienne table n'avait pas cette colonne)
--    Filtre : salle_id doit exister dans salles (FK), user_id dans users.
-- ---------------------------------------------------------------

\echo '[8/9] Salles suivies...'

INSERT INTO user_salles (user_id, salle_id, tm_venue_id, created_at)
SELECT us.user_id, us.salle_id, us.tm_venue_id, now()
FROM dblink(:'source_conn',
    $$SELECT user_id, salle_id, tm_venue_id FROM user_salles$$)
AS us(user_id text, salle_id text, tm_venue_id text)
WHERE us.user_id  IN (SELECT id   FROM users)
  AND us.salle_id IN (SELECT id   FROM salles)
ON CONFLICT (user_id, salle_id) DO NOTHING;

SELECT COUNT(*) AS "user_salles migrés" FROM user_salles;

-- ---------------------------------------------------------------
-- 9. user_matches
--    Transformations :
--      - vu_le supprimée (#H) — notifie_le + masque_le conservées
--    Filtre : user_id ET concert_id doivent exister dans la destination
--    (les concerts bandsintown ont été exclus → leurs matches sont orphelins).
-- ---------------------------------------------------------------

\echo '[9/9] Matches concerts → utilisateurs...'

INSERT INTO user_matches (user_id, concert_id, notifie_le, masque_le)
SELECT um.user_id, um.concert_id, um.notifie_le, um.masque_le
FROM dblink(:'source_conn',
    $$SELECT user_id, concert_id, notifie_le, masque_le FROM user_matches$$)
AS um(user_id text, concert_id uuid, notifie_le timestamptz, masque_le timestamptz)
WHERE um.user_id   IN (SELECT id FROM users)
  AND um.concert_id IN (SELECT id FROM concerts)
ON CONFLICT (user_id, concert_id) DO NOTHING;

SELECT COUNT(*) AS "user_matches migrés" FROM user_matches;

-- ---------------------------------------------------------------
-- 10. app_config
--     Copiée telle quelle — même APP_CONFIG_SECRET sur source et destination.
--     DO UPDATE : si re-jeu du script, on prend les valeurs les plus récentes.
-- ---------------------------------------------------------------

\echo '[10/10] Configuration applicative (chiffrée)...'

INSERT INTO app_config (cle, valeur_chiffree, mis_a_jour_le)
SELECT cle, valeur_chiffree, mis_a_jour_le
FROM dblink(:'source_conn',
    $$SELECT cle, valeur_chiffree, mis_a_jour_le FROM app_config$$)
AS src(cle text, valeur_chiffree text, mis_a_jour_le timestamptz)
ON CONFLICT (cle) DO UPDATE
    SET valeur_chiffree = EXCLUDED.valeur_chiffree,
        mis_a_jour_le   = EXCLUDED.mis_a_jour_le;

SELECT COUNT(*) AS "app_config migrés" FROM app_config;

-- ---------------------------------------------------------------
-- Validation finale — comptages destination vs source
-- ---------------------------------------------------------------

\echo ''
\echo '╔══════════════════════════════════════════════════════╗'
\echo '║              Validation finale                       ║'
\echo '╚══════════════════════════════════════════════════════╝'

SELECT
    'users'            AS table_name,
    COUNT(*)           AS nb_destination
FROM users
UNION ALL SELECT 'concerts',         COUNT(*) FROM concerts
UNION ALL SELECT 'salles',           COUNT(*) FROM salles
UNION ALL SELECT 'accounts',         COUNT(*) FROM accounts
UNION ALL SELECT 'user_artistes',    COUNT(*) FROM user_artistes
UNION ALL SELECT 'user_config',      COUNT(*) FROM user_config
UNION ALL SELECT 'user_salles',      COUNT(*) FROM user_salles
UNION ALL SELECT 'user_matches',     COUNT(*) FROM user_matches
UNION ALL SELECT 'push_abonnements', COUNT(*) FROM push_abonnements
UNION ALL SELECT 'app_config',       COUNT(*) FROM app_config
ORDER BY 1;

COMMIT;

\echo ''
\echo '✓ Migration terminée avec succès.'
\echo ''
\echo 'Prochaines étapes :'
\echo '  1. Démarrer le service Java web (port 8080)'
\echo '  2. Vérifier : curl http://localhost:8080/actuator/health'
\echo '  3. Démarrer le service Java worker'
\echo '  4. Tester le login (scrypt → bcrypt re-hashé au premier login)'
\echo '  5. TOTP admin : re-configurer depuis /parametres#deux-facteurs'
\echo '  6. Passer la checklist de validation Phase 7'
