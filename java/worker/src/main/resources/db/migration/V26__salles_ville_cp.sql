-- V26 : rattrapage du jeu de migrations worker, jamais synchronisé avec web V34 (#697)
-- EnrichissementSallesService (worker) écrit/lit salles.ville depuis 2.6.0 — colonne absente du
-- schéma worker, jamais détecté faute de test d'intégration exerçant selectionnerCandidats contre
-- une vraie base. Sans impact prod, où seul web migre — trouvé en session 2.7.3 en testant #994.
ALTER TABLE salles
    ADD COLUMN IF NOT EXISTS ville text,
    ADD COLUMN IF NOT EXISTS cp    varchar(5);
