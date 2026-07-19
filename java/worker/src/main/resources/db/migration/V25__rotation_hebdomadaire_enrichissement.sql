-- V25 : rotation hebdomadaire par jour ISO pour l'enrichissement Wikidata/OSM/Deezer (#994)
-- Même migration que web V46 — voir ce fichier pour le détail des motivations.
CREATE SEQUENCE enrichissement_jour_batch_seq;

ALTER TABLE salles
  ADD COLUMN jour_batch smallint NOT NULL
  DEFAULT (((nextval('enrichissement_jour_batch_seq') - 1) % 7) + 1);

ALTER TABLE artistes
  ADD COLUMN jour_batch smallint NOT NULL
  DEFAULT (((nextval('enrichissement_jour_batch_seq') - 1) % 7) + 1);
