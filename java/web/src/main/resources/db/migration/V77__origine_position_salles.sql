-- V77__origine_position_salles.sql
--
-- Origine des coordonnées d'une salle : Wikidata (fait autorité), consensus des lieux sources,
-- ou géocodage Nominatim de son nom (homonymes possibles : « Comédie de Paris » géocodée à
-- Montpellier). Sans cette origine, une position géocodée fausse ne pouvait pas être remplacée
-- par celle, publiée, de ses lieux sources.
ALTER TABLE salles ADD COLUMN origine_position text
    CHECK (origine_position IN ('wikidata', 'sources', 'geocodage'));

-- Avant cette version, seuls l'enrichissement Wikidata et le géocodage écrivaient ces colonnes.
UPDATE salles
SET origine_position = CASE WHEN wikidata_id IS NOT NULL THEN 'wikidata' ELSE 'geocodage' END
WHERE latitude IS NOT NULL;
