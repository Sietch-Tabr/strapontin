-- V37__corriger_contrainte_unique_concerts.sql
-- #738 : La contrainte uq_concerts_artiste_date_heure_salle porte sur salle_norm (texte).
-- Depuis V24, l'identité d'une salle est portée par salle_uuid — deux sources nommant
-- la même salle différemment génèrent des doublons malgré un salle_uuid identique.
--
-- Stratégie :
--   1. Supprimer les doublons (garder le concert à l'id le plus petit par groupe).
--      user_matches a ON DELETE CASCADE, les références orphelines disparaissent automatiquement.
--   2. Recréer la contrainte sur salle_uuid.
--
-- Portée du dédoublonnage : couvre les cas salle_uuid IS NOT NULL (doublon inter-sources)
-- et salle_uuid IS NULL (NULLS NOT DISTINCT → deux concerts sans salle résolue, même artiste/date/heure).

WITH doublons AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY artiste_norm, date, heure, salle_uuid
               ORDER BY id
           ) AS rn
    FROM concerts
)
DELETE FROM concerts WHERE id IN (SELECT id FROM doublons WHERE rn > 1);

ALTER TABLE concerts DROP CONSTRAINT uq_concerts_artiste_date_heure_salle;
ALTER TABLE concerts
    ADD CONSTRAINT uq_concerts_artiste_date_heure_salle
    UNIQUE NULLS NOT DISTINCT (artiste_norm, date, heure, salle_uuid);
