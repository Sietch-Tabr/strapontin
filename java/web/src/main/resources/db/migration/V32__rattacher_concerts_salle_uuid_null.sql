-- V32__rattacher_concerts_salle_uuid_null.sql
-- Rattachement des concerts futurs dont salle_uuid est NULL malgré une salle existante (#691).
--
-- 9 concerts futurs ont salle_uuid IS NULL alors qu'une salle avec le même nom_norm
-- existe dans salles. La résolution UUID-first n'a pas pu les raccrocher (salleSourceId absent),
-- et reconcilierSalles ne remplit pas salle_uuid.
--
-- Jointure exacte sur nom_norm : sans risque de faux positifs, contrairement au seuil pg_trgm.

UPDATE concerts c
SET salle_uuid = s.id
FROM salles s
WHERE c.salle_uuid IS NULL
  AND c.date >= CURRENT_DATE
  AND c.salle_norm = s.nom_norm;
