-- V14__corriger_contrainte_unique_concerts.sql
-- #738 : Équivalent worker de V37 web (tests d'intégration Testcontainers).

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
