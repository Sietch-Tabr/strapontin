-- Salles dont les concerts rattachés couvrent des départements réellement
-- distincts — signal fort de fusion/résolution incorrecte entre deux lieux
-- réels. Requête figée volontairement (voir HISTORIQUE.md, écart "1 puis 8"
-- du 06/08/2026 causé par une requête retapée légèrement différente d'un
-- check à l'autre) : toujours utiliser ce fichier tel quel, jamais le
-- réécrire de mémoire en heredoc.
-- 29/09/2026 (#1275) : le filtre "salle_sources > 1" a été retiré — il
-- excluait structurellement toute contamination géographique produite par
-- une résolution floue erronée sur une salle à mapping source unique (cas
-- réel trouvé : L'Embarcadère/Aubervilliers, 1 seule ligne salle_sources,
-- concerts sur 2 départements distincts, invisible tant que ce filtre
-- restait en place).
SELECT s.id, s.nom, s.ville, s.dept,
  count(DISTINCT c.dept) AS nb_depts_distincts, array_agg(DISTINCT c.dept) AS depts,
  array_agg(DISTINCT c.ville) AS villes_concerts
FROM salles s
JOIN concerts c ON c.salle_id = s.id
GROUP BY s.id, s.nom, s.ville, s.dept
HAVING count(DISTINCT c.dept) > 1
ORDER BY s.nom;
