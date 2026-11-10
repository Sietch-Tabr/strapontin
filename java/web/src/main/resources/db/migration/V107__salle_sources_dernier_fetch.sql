-- V107__salle_sources_dernier_fetch.sql
--
-- Date de la dernière interrogation d'un lieu de billetterie : le rafraîchissement par lieu des
-- fiches non revues se règle sur elle plutôt que sur le compteur d'absences des fiches, qu'une
-- fiche encore en vente peut avoir atteint avant le premier passage.
ALTER TABLE salle_sources ADD COLUMN dernier_fetch_le timestamptz;
