-- V10__concerts_salle_cle.sql
-- Matérialise une clé de salle sargable sur concerts (ticket #585).
--
-- Les requêtes de matching salle comparaient regexp_replace(salle_norm, '[^a-z0-9]', '', 'g')
-- (non-sargable, seq scan) à salles.id = Normalisation.normaliserSalleCle(nom). La colonne
-- générée salle_cle reproduit cette normalisation et est indexée, rendant les filtres sargables.
-- Generated STORED : toujours synchrone avec salle_norm, aucun calcul applicatif à maintenir.

ALTER TABLE concerts
    ADD COLUMN salle_cle text GENERATED ALWAYS AS (
        regexp_replace(salle_norm, '[^a-z0-9]', '', 'g')
    ) STORED;

CREATE INDEX idx_concerts_salle_cle ON concerts USING btree (salle_cle);
