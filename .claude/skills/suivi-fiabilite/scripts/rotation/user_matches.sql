-- Rotation section 9 : couverture exhaustive des user_matches (cohérence
-- suit_artiste/suit_salle), par lot successif, curseur sur concert_id.
-- Requête figée (même pattern que scripts/checks/ — voir HISTORIQUE.md) :
-- toujours utiliser ce fichier tel quel, jamais le réécrire de mémoire.
-- __CURSEUR__/__LOT__ substitués par run_rotation.py.
SELECT um.user_id, um.concert_id, c.artiste, c.artiste_id, c.salle, c.salle_id,
  EXISTS (SELECT 1 FROM user_artistes ua WHERE ua.user_id = um.user_id AND ua.artiste_id = c.artiste_id) AS suit_artiste,
  EXISTS (SELECT 1 FROM user_salles us WHERE us.user_id = um.user_id AND us.salle_id = c.salle_id) AS suit_salle
FROM user_matches um
JOIN concerts c ON c.id = um.concert_id
WHERE um.concert_id::text > '__CURSEUR__'
ORDER BY um.concert_id
LIMIT __LOT__;
