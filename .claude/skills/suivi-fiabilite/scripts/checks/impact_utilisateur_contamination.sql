-- Utilisateurs suivant réellement une salle actuellement signalée comme
-- contaminée (fusion de lieux réels distincts). orphelins_fk vérifie que
-- user_salles.salle_id existe ; ce check vérifie que la salle existante est
-- fiable, pas seulement présente. Duplique volontairement la logique de
-- geo_incoherence_salles.sql (à garder synchronisée si ce dernier change) :
-- nécessaire pour un JOIN direct contre user_salles/user_artistes, pas
-- exprimable en réutilisant l'autre fichier tel quel.
-- 30/09/2026 : resynchronisé avec geo_incoherence_salles.sql (#1275, 29/09) —
-- le filtre "salle_sources > 1" avait été retiré là-bas mais pas ici, ce qui
-- rendait ce check aveugle au même angle mort (salle contaminée par
-- résolution floue sur mapping source unique, ex. L'Embarcadère).
WITH salles_incoherentes AS (
  SELECT s.id, s.nom, count(DISTINCT c.dept) AS nb_depts_distincts,
    array_agg(DISTINCT c.dept) AS depts
  FROM salles s
  JOIN concerts c ON c.salle_id = s.id
  GROUP BY s.id, s.nom
  HAVING count(DISTINCT c.dept) > 1
),
salles_suffixe AS (
  SELECT s1.id FROM salles s1
  JOIN salles s2 ON s1.id <> s2.id
    AND s2.nom_norm LIKE '%' || s1.nom_norm || '%' AND s1.nom_norm <> s2.nom_norm
  WHERE s2.wikidata_id IS NULL AND s1.wikidata_id IS NOT NULL AND length(s1.nom_norm) > 4
  UNION
  SELECT s2.id FROM salles s1
  JOIN salles s2 ON s1.id <> s2.id
    AND s2.nom_norm LIKE '%' || s1.nom_norm || '%' AND s1.nom_norm <> s2.nom_norm
  WHERE s2.wikidata_id IS NULL AND s1.wikidata_id IS NOT NULL AND length(s1.nom_norm) > 4
)
SELECT DISTINCT us.user_id, s.id AS salle_id, s.nom,
  si.nb_depts_distincts, si.depts,
  (ss.id IS NOT NULL) AS via_suffixe
FROM user_salles us
JOIN salles s ON s.id = us.salle_id
LEFT JOIN salles_incoherentes si ON si.id = s.id
LEFT JOIN salles_suffixe ss ON ss.id = s.id
WHERE si.id IS NOT NULL OR ss.id IS NOT NULL
ORDER BY s.nom;
