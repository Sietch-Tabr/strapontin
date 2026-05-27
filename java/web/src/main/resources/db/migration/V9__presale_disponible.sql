ALTER TABLE concerts DROP COLUMN disponible;
ALTER TABLE concerts ADD COLUMN disponible boolean GENERATED ALWAYS AS (
    statut IS NULL OR statut IN ('onsale', 'presale', 'rescheduled', 'postponed')
) STORED;
CREATE INDEX idx_concerts_disponible ON concerts USING btree (disponible);
