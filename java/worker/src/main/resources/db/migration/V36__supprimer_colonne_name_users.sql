-- Rattrapage du jeu de migrations web (V18) : colonne déjà supprimée côté prod, jamais
-- répercutée dans le schéma de test worker.
ALTER TABLE users DROP COLUMN name;
