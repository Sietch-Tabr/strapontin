-- backfill-621.sql — Ticket #621 : identité stable salle/artiste
--
-- Peupler les tables artistes, salle_sources, artiste_sources et les colonnes UUID
-- depuis les données existantes. Opération idempotente (ON CONFLICT DO NOTHING).
--
-- UTILISATION :
--   Dry-run (ne modifie rien) : exécuter tel quel — se termine par ROLLBACK.
--   Appliquer en prod          : remplacer ROLLBACK par COMMIT à la fin.
--
-- Prérequis : migration V23 déjà appliquée (salle_uuid, artistes, salle_sources, etc.)

BEGIN;

-- 1. Créer les artistes depuis les abonnements utilisateurs (artiste_affiche distinct sur nom_norm)
INSERT INTO artistes (nom, nom_norm)
SELECT DISTINCT ON (artiste_normalise) artiste_affiche, artiste_normalise
FROM user_artistes
ORDER BY artiste_normalise, artiste_affiche
ON CONFLICT (nom_norm) DO NOTHING;

-- 2. Correspondance TM attraction_id → artiste interne
INSERT INTO artiste_sources (artiste_id, source, identifiant_externe)
SELECT a.id, 'ticketmaster', ua.attraction_id
FROM user_artistes ua
JOIN artistes a ON a.nom_norm = ua.artiste_normalise
WHERE ua.attraction_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 3. user_artistes.artiste_id (rétroactif)
UPDATE user_artistes ua
SET artiste_id = a.id
FROM artistes a
WHERE a.nom_norm = ua.artiste_normalise
  AND ua.artiste_id IS NULL;

-- 4. Correspondance TM tm_venue_id → salle interne
INSERT INTO salle_sources (salle_id, source, identifiant_externe)
SELECT s.salle_uuid, 'ticketmaster', us.tm_venue_id
FROM user_salles us
JOIN salles s ON s.id = us.salle_id
WHERE us.tm_venue_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 5. user_salles.salle_uuid (rétroactif)
UPDATE user_salles us
SET salle_uuid = s.salle_uuid
FROM salles s
WHERE s.id = us.salle_id
  AND us.salle_uuid IS NULL;

-- 6. concerts.salle_uuid via correspondance exacte salle_cle ↔ salles.id
UPDATE concerts c
SET salle_uuid = s.salle_uuid
FROM salles s
WHERE s.id = c.salle_cle
  AND c.salle_uuid IS NULL;

-- 7. concerts.artiste_id via correspondance exacte artiste_norm
UPDATE concerts c
SET artiste_id = a.id
FROM artistes a
WHERE a.nom_norm = c.artiste_norm
  AND c.artiste_id IS NULL;

-- Rapport de couverture post-backfill
SELECT
    (SELECT COUNT(*)  FROM concerts)                               AS concerts_total,
    (SELECT COUNT(*)  FROM concerts WHERE salle_uuid IS NULL)      AS concerts_sans_salle_uuid,
    (SELECT COUNT(*)  FROM concerts WHERE artiste_id IS NULL)      AS concerts_sans_artiste_id,
    (SELECT COUNT(*)  FROM user_salles)                            AS user_salles_total,
    (SELECT COUNT(*)  FROM user_salles WHERE salle_uuid IS NULL)   AS user_salles_sans_uuid,
    (SELECT COUNT(*)  FROM user_artistes)                          AS user_artistes_total,
    (SELECT COUNT(*)  FROM user_artistes WHERE artiste_id IS NULL) AS user_artistes_sans_artiste_id,
    (SELECT COUNT(*)  FROM artistes)                               AS artistes_crees,
    (SELECT COUNT(*)  FROM salle_sources)                          AS salle_sources_crees,
    (SELECT COUNT(*)  FROM artiste_sources)                        AS artiste_sources_crees;

ROLLBACK;
-- Remplacer par COMMIT après avoir vérifié le rapport ci-dessus.
