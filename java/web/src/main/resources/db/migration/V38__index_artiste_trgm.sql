-- V38 : index GIN trigram sur concerts.artiste (#737)
-- concerts.artiste_norm est indexé (V12) mais concerts.artiste ne l'est pas,
-- alors que DecouverteService l'utilise dans un ILIKE :q.
CREATE INDEX IF NOT EXISTS idx_concerts_artiste_trgm ON concerts USING gin (artiste gin_trgm_ops);
