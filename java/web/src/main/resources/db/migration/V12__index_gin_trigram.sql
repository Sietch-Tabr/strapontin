CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_concerts_artiste_norm_trgm ON concerts USING gin (artiste_norm gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_concerts_salle_trgm ON concerts USING gin (salle gin_trgm_ops);
