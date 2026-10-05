-- Salles dont le nom_norm est contenu dans celui d'une autre (variante
-- courte déjà enrichie vs variante longue avec suffixe ville/qualificatif
-- jamais enrichie). Découvre des paires qu'un simple regroupement sur
-- salle_norm identique ne trouve pas. Fort taux de faux positifs légitimes
-- (sous-salles d'un même complexe, ex. La Seine Musicale/Auditorium
-- Devedjian) — chaque paire doit être vérifiée individuellement contre
-- concerts.ville/dept avant toute conclusion, jamais classée en bloc.
SELECT s1.id AS id_court, s1.nom AS nom_court, s1.wikidata_id AS wikidata_court,
  s2.id AS id_long, s2.nom AS nom_long
FROM salles s1
JOIN salles s2 ON s1.id <> s2.id
  AND s2.nom_norm LIKE '%' || s1.nom_norm || '%' AND s1.nom_norm <> s2.nom_norm
WHERE s2.wikidata_id IS NULL AND s1.wikidata_id IS NOT NULL AND length(s1.nom_norm) > 4
ORDER BY s1.nom;
