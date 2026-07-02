-- V18__enrichissement_artistes.sql
-- Enrichissement artistes via Wikidata/Deezer (#711/#716) : Q-ID Wikidata, ID Deezer et nom
-- canonique affiché. Même règle que salles.nom_affiche : override manuel prioritaire (#710).

ALTER TABLE artistes
    ADD COLUMN wikidata_id TEXT,
    ADD COLUMN deezer_id   TEXT,
    ADD COLUMN nom_affiche TEXT;

CREATE UNIQUE INDEX idx_artistes_wikidata_id ON artistes (wikidata_id) WHERE wikidata_id IS NOT NULL;
