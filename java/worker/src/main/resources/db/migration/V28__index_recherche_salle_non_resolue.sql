-- V28__index_recherche_salle_non_resolue.sql
--
-- Rattrapage du jeu de migrations web V49 — schéma dual, spring.flyway.enabled=false en prod côté
-- worker, mais nécessaire pour les tests d'intégration Testcontainers du worker qui partagent le
-- même schéma que web.

ALTER TABLE concerts
    ADD COLUMN salle_cle text GENERATED ALWAYS AS (
        regexp_replace(salle_norm, '[^a-z0-9]', '', 'g')
    ) STORED;

CREATE INDEX idx_concerts_salle_cle_trgm ON concerts USING gin (salle_cle gin_trgm_ops);

ALTER TABLE salles
    ADD COLUMN nom_cle text GENERATED ALWAYS AS (
        regexp_replace(nom_norm, '[^a-z0-9]', '', 'g')
    ) STORED;

CREATE INDEX idx_salles_nom_cle_trgm ON salles USING gin (nom_cle gin_trgm_ops);
