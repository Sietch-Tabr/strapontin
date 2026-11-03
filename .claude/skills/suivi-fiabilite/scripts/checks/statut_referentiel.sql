-- Statut référentiel tel qu'un administrateur le voit (ReferentielSallesService et
-- ReferentielArtistesService) : salles a_jour / a_verifier (suspect depuis moins de 24 h) /
-- invalide (suspect depuis plus de 24 h) ; artistes a_jour / invalide (intervention requise).
SELECT 'salles' AS entite, statut, count(*) AS nombre
FROM (
  SELECT s.id,
         CASE
           WHEN MIN(ss.suspect_depuis) IS NULL THEN 'a_jour'
           WHEN MIN(ss.suspect_depuis) < now() - interval '24 hours' THEN 'invalide'
           ELSE 'a_verifier'
         END AS statut
  FROM salles s
  LEFT JOIN salle_sources ss ON ss.salle_id = s.id
  GROUP BY s.id
) x
GROUP BY statut
UNION ALL
SELECT 'artistes', statut, count(*)
FROM (
  SELECT a.id,
         CASE WHEN MIN(asrc.necessite_intervention_le) IS NULL THEN 'a_jour' ELSE 'invalide' END AS statut
  FROM artistes a
  LEFT JOIN artiste_sources asrc ON asrc.artiste_id = a.id
  GROUP BY a.id
) y
GROUP BY statut
ORDER BY 1, 2;
