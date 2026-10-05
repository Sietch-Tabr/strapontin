-- V81__ville_salles_commune.sql
--
-- L'enrichissement Wikidata écrivait comme ville la valeur brute de P131, qui désigne souvent un
-- arrondissement ou un quartier (« 18e arrondissement de Paris », « Saint-Thomas-d'Aquin ») :
-- 58 salles en production au 3 octobre 2026. La commune passe désormais par le code INSEE, mais
-- une ville déjà remplie n'est jamais réécrite. Le département 75 est la seule commune de Paris.
UPDATE salles
SET ville = 'Paris'
WHERE dept = '75'
  AND ville IS DISTINCT FROM 'Paris'
  AND ville IS NOT NULL;

UPDATE salles
SET ville = CASE WHEN ville ~* 'arrondissement de lyon$' THEN 'Lyon' ELSE 'Marseille' END
WHERE ville ~* 'arrondissement de (lyon|marseille)$';
