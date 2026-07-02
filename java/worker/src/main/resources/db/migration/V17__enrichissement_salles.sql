-- V17__enrichissement_salles.sql
-- Enrichissement salles via Wikidata/OSM (#711) : Q-ID Wikidata et nom canonique affiché.
-- nom_affiche est un override manuel prioritaire (admin #710) sur le nom brut des sources ;
-- une fois posé, l'enrichissement automatique ne l'écrase plus (COALESCE côté application).

ALTER TABLE salles
    ADD COLUMN wikidata_id TEXT,
    ADD COLUMN nom_affiche TEXT;

CREATE UNIQUE INDEX idx_salles_wikidata_id ON salles (wikidata_id) WHERE wikidata_id IS NOT NULL;
