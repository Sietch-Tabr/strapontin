-- Rotation section 9 : couverture exhaustive des users/user_config, par lot
-- successif. Requête figée (même pattern que scripts/checks/ — voir
-- HISTORIQUE.md) : toujours utiliser ce fichier tel quel, jamais le réécrire
-- de mémoire. __CURSEUR__/__LOT__ substitués par run_rotation.py.
SELECT u.id, u.role, uc.departements, uc.notif_artistes, uc.notif_salles
FROM users u
LEFT JOIN user_config uc ON uc.user_id = u.id
WHERE u.id::text > '__CURSEUR__'
ORDER BY u.id
LIMIT __LOT__;
