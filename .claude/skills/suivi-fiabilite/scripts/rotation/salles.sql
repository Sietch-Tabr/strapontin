-- Rotation section 9 : couverture exhaustive des salles avec wikidata_id, par
-- lot successif. Requête figée (même pattern que scripts/checks/ — voir
-- HISTORIQUE.md) : toujours utiliser ce fichier tel quel, jamais le réécrire
-- de mémoire. __CURSEUR__/__LOT__ substitués par run_rotation.py.
SELECT id, nom, ville, dept, wikidata_id
FROM salles
WHERE wikidata_id IS NOT NULL AND id::text > '__CURSEUR__'
ORDER BY id
LIMIT __LOT__;
