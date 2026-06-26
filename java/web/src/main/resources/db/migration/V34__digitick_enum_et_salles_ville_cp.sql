-- #697 : ajouter 'digitick' à l'ENUM source_concert et colonnes ville/cp sur salles.
ALTER TYPE source_concert ADD VALUE IF NOT EXISTS 'digitick';

ALTER TABLE salles
    ADD COLUMN IF NOT EXISTS ville text,
    ADD COLUMN IF NOT EXISTS cp    varchar(5);
