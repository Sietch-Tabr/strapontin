-- V25__heure_cle_conflit_concerts.sql
-- #640 : inclure heure dans la clé de conflit concerts (NULLS NOT DISTINCT).
--
-- Avant : UNIQUE (artiste_norm, date, salle_norm) — deux séances du même artiste
--         le même jour (matinée + soirée) fusionnaient en un seul enregistrement.
-- Après : UNIQUE NULLS NOT DISTINCT (artiste_norm, date, heure, salle_norm) —
--         deux séances avec des heures différentes coexistent ; deux concerts sans
--         heure (NULL) restent en conflit (NULLS NOT DISTINCT : NULL = NULL).
--
-- Prérequis : PostgreSQL 15+ (NULLS NOT DISTINCT). Vérifier avec : SELECT version();

ALTER TABLE concerts DROP CONSTRAINT uq_concerts_artiste_date_salle;
ALTER TABLE concerts
    ADD CONSTRAINT uq_concerts_artiste_date_heure_salle
    UNIQUE NULLS NOT DISTINCT (artiste_norm, date, heure, salle_norm);
