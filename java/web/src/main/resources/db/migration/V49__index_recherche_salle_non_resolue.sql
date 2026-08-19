-- V49__index_recherche_salle_non_resolue.sql
--
-- SalleRepository.rechercherEnBase filtre regexp_replace(salle_norm/nom_norm, '[^a-z0-9]', '', 'g')
-- LIKE :pattern, sans aucun index depuis que V24 a supprimé concerts.salle_cle (créée par V19 pour
-- exactement ce filtre) — à l'époque remplacée par la résolution UUID salle_id, mais
-- rechercherEnBase interroge justement les concerts dont salle_id est encore NULL, jamais couverts
-- par cette résolution. Restaure une colonne générée équivalente sur concerts, et son pendant sur
-- salles (nom_cle — salle_cle y désigne déjà l'ancienne clé naturelle texte, sans rapport).

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
