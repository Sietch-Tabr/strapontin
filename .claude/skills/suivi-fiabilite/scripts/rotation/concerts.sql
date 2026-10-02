-- Rotation section 9 : couverture exhaustive des concerts futurs, par lot
-- successif. Requête figée (même pattern que scripts/checks/ — voir
-- HISTORIQUE.md) : toujours utiliser ce fichier tel quel, jamais le réécrire
-- de mémoire. __CURSEUR__/__LOT__ substitués par run_rotation.py.
SELECT id, artiste, date, heure, ville, salle, source, artiste_id, salle_id
FROM concerts
WHERE date >= current_date AND id::text > '__CURSEUR__'
ORDER BY id
LIMIT __LOT__;
