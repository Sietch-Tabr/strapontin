-- V22 : renommer salle_uuid en salle_id (#933, miroir de V45 web)
-- Cohérence avec la convention FK {table_referencee}_id, déjà respectée par artiste_id et
-- salle_sources.salle_id — salles.salle_uuid avait déjà été renommée en salles.id (V10).
ALTER TABLE concerts RENAME COLUMN salle_uuid TO salle_id;
ALTER TABLE user_salles RENAME COLUMN salle_uuid TO salle_id;
ALTER INDEX idx_concerts_salle_uuid RENAME TO idx_concerts_salle_id;
