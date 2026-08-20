-- V51__coherence_wikidata_signalee.sql
--
-- CoherenceWikidataSalleService (worker.sante) journalisait chaque nuit, sans mémoire d'un état
-- déjà signalé, la moindre divergence wikidata_id/ville — y compris des salles déjà investiguées
-- et confirmées légitimes à plusieurs reprises (agglomération, imprécision du P131 Wikidata
-- lui-même). Cette colonne retient la signature de la dernière incohérence déjà journalisée par
-- salle : le service ne re-loggue que si elle change (nouvelle incohérence, ou réapparition après
-- résolution), silence sinon.
ALTER TABLE salles ADD COLUMN coherence_wikidata_signalee text;
