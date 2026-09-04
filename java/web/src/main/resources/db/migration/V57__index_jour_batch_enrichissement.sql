-- Index partiel calqué sur le filtre exact du cycle nocturne d'enrichissement
-- (WHERE wikidata_id IS NULL AND jour_batch = ...) : la table entière était scannée
-- chaque nuit pour n'en retenir qu'un septième, l'appariement wikidata_id/jour_batch.
CREATE INDEX idx_salles_jour_batch_sans_wikidata ON salles (jour_batch) WHERE wikidata_id IS NULL;
CREATE INDEX idx_artistes_jour_batch_sans_wikidata ON artistes (jour_batch) WHERE wikidata_id IS NULL;
