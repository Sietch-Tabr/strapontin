-- V11__heure_cle_conflit_concerts.sql
-- #640 : inclure heure dans la clé de conflit concerts (NULLS NOT DISTINCT).
-- Voir V25 (module web) pour le détail.

ALTER TABLE concerts DROP CONSTRAINT uq_concerts_artiste_date_salle;
ALTER TABLE concerts
    ADD CONSTRAINT uq_concerts_artiste_date_heure_salle
    UNIQUE NULLS NOT DISTINCT (artiste_norm, date, heure, salle_norm);
