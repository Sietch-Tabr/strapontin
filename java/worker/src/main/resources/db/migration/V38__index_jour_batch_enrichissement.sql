-- Même migration que web V57 — voir ce fichier pour le détail des motivations.
CREATE INDEX idx_salles_jour_batch_sans_wikidata ON salles (jour_batch) WHERE wikidata_id IS NULL;
CREATE INDEX idx_artistes_jour_batch_sans_wikidata ON artistes (jour_batch) WHERE wikidata_id IS NULL;
