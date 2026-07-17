-- V21 : suppression prix_min/prix_max (#931, miroir de V33 web)
-- Jeu de migrations dual web/worker : ces colonnes n'ont jamais servi (jamais alimentées
-- par aucune source), déjà supprimées côté web mais jamais côté worker.
ALTER TABLE concerts
    DROP COLUMN IF EXISTS prix_min,
    DROP COLUMN IF EXISTS prix_max;
