-- V10__nettoyage_schema_uuid.sql
-- Nettoyage post-backfill #621 : réplique de la migration web V24.
-- Flyway worker est activé uniquement en test (Testcontainers) ; en prod seul web migre.

ALTER TABLE user_salles    ALTER COLUMN salle_uuid  SET NOT NULL;
ALTER TABLE user_artistes  ALTER COLUMN artiste_id  SET NOT NULL;

ALTER TABLE user_salles DROP CONSTRAINT user_salles_pkey;
ALTER TABLE user_salles ADD CONSTRAINT user_salles_pkey PRIMARY KEY (user_id, salle_uuid);
ALTER TABLE user_salles DROP COLUMN salle_id;
ALTER TABLE user_salles DROP COLUMN tm_venue_id;
DROP INDEX IF EXISTS idx_user_salles_salle_uuid;

ALTER TABLE user_artistes DROP CONSTRAINT user_artistes_pkey;
ALTER TABLE user_artistes ADD CONSTRAINT user_artistes_pkey PRIMARY KEY (user_id, artiste_id);
ALTER TABLE user_artistes DROP COLUMN artiste_normalise;
DROP INDEX IF EXISTS idx_user_artistes_norm;
DROP INDEX IF EXISTS idx_user_artistes_artiste_id;

ALTER TABLE concerts DROP COLUMN salle_cle;

ALTER TABLE salles RENAME COLUMN id        TO salle_cle;
ALTER TABLE salles DROP CONSTRAINT salles_pkey;
ALTER TABLE salles ADD CONSTRAINT uq_salles_salle_cle UNIQUE (salle_cle);
ALTER TABLE salles RENAME COLUMN salle_uuid TO id;
-- CASCADE supprime les FK dépendantes : salle_sources.salle_id, concerts.salle_uuid, user_salles.salle_uuid
ALTER TABLE salles DROP CONSTRAINT salles_salle_uuid_key CASCADE;
ALTER TABLE salles ADD CONSTRAINT salles_pkey PRIMARY KEY (id);
-- Recréation des FK pointant vers le nouveau PK uuid
ALTER TABLE salle_sources ADD FOREIGN KEY (salle_id)  REFERENCES salles(id) ON DELETE CASCADE;
ALTER TABLE concerts       ADD FOREIGN KEY (salle_uuid) REFERENCES salles(id) ON DELETE SET NULL;
ALTER TABLE user_salles    ADD FOREIGN KEY (salle_uuid) REFERENCES salles(id) ON DELETE CASCADE;
