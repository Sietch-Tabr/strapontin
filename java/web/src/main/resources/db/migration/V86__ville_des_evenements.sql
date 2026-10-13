-- V86__ville_des_evenements.sql
--
-- Une source peut omettre la ville d'un lieu pourtant connu (Eventim pour le Stade de France ou
-- la Plenitude Arena) : l'événement reprend désormais la ville de sa salle. Rattrapage des
-- événements déjà enregistrés sans ville, puis une ville ne peut plus être une chaîne vide, que
-- les contrôles de complétude confondaient avec une valeur renseignée.
UPDATE evenements e SET ville = s.ville
FROM salles s
WHERE s.id = e.salle_id AND (e.ville IS NULL OR e.ville = '') AND s.ville <> '';

UPDATE evenements SET ville = NULL WHERE ville = '';
UPDATE salles SET ville = NULL WHERE ville = '';

ALTER TABLE evenements ADD CONSTRAINT ck_evenements_ville_non_vide CHECK (ville <> '');
ALTER TABLE salles ADD CONSTRAINT ck_salles_ville_non_vide CHECK (ville <> '');
