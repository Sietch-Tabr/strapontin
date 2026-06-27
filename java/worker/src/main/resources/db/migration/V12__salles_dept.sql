-- #702 : ajouter colonne dept sur salles (réplique de web V34+V35).
-- Permet à chercherSalleParTokens() de filtrer par département pour éviter les faux positifs
-- entre salles homonymes de villes distinctes.

ALTER TABLE salles ADD COLUMN IF NOT EXISTS dept varchar(5);
