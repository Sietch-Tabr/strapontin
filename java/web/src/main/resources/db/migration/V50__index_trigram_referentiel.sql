-- V50__index_trigram_referentiel.sql
--
-- ReferentielSallesService/ReferentielArtistesService filtrent nom/ville en ILIKE '%...%' sans
-- index — sans impact mesurable au volume actuel, mais deviendra un scan séquentiel systématique
-- si le volume augmente. gin_trgm_ops supporte ILIKE comme LIKE.

CREATE INDEX idx_salles_nom_trgm ON salles USING gin (nom gin_trgm_ops);
CREATE INDEX idx_salles_ville_trgm ON salles USING gin (ville gin_trgm_ops);
CREATE INDEX idx_artistes_nom_trgm ON artistes USING gin (nom gin_trgm_ops);
