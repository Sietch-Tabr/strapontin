-- V6__concerts_salle_cle.sql
-- Matérialise une clé de salle sargable sur concerts (ticket #585).
--
-- Réplique de la migration web V19 (les jeux Flyway web et worker décrivent le même schéma).
-- Generated STORED : toujours synchrone avec salle_norm, indexée pour les filtres de matching salle.

ALTER TABLE concerts
    ADD COLUMN salle_cle text GENERATED ALWAYS AS (
        regexp_replace(salle_norm, '[^a-z0-9]', '', 'g')
    ) STORED;

CREATE INDEX idx_concerts_salle_cle ON concerts USING btree (salle_cle);
