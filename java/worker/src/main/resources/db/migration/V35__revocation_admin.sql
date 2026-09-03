-- Rattrapage du jeu de migrations web (V22) : WorkerRevocationService lit/écrit cette colonne
-- en SQL brut, absente jusqu'ici du schéma de test worker.
ALTER TABLE users ADD COLUMN revocation_admin_le TIMESTAMP WITH TIME ZONE;
