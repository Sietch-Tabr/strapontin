-- V75__reference_inactivite_comptes.sql
--
-- derniere_connexion_le (V64) n'a reçu aucune valeur initiale : le décompte d'inactivité des
-- comptes existants se rabattait sur leur date de création, relançant et programmant la
-- suppression de comptes actifs. Les comptes activés sans date prennent celle de cette
-- correction comme référence, et toute suppression programmée pour eux est annulée.
UPDATE users
SET purge_le = NULL
WHERE derniere_connexion_le IS NULL
  AND purge_le IS NOT NULL
  AND must_change_credentials = false;

UPDATE users
SET derniere_connexion_le = now()
WHERE derniere_connexion_le IS NULL
  AND must_change_credentials = false;
