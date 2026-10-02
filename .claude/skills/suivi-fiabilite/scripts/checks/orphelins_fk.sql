-- Intégrité référentielle : toute ligne non nulle ici est un signal fort
-- (les contraintes FK ne devraient jamais avoir d'orphelin en pratique).
SELECT 'concerts.salle_id' AS cle, count(*) FROM concerts c
  WHERE c.salle_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM salles s WHERE s.id = c.salle_id)
UNION ALL
SELECT 'concerts.artiste_id', count(*) FROM concerts c
  WHERE c.artiste_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM artistes a WHERE a.id = c.artiste_id)
UNION ALL
SELECT 'user_artistes.artiste_id', count(*) FROM user_artistes ua
  WHERE NOT EXISTS (SELECT 1 FROM artistes a WHERE a.id = ua.artiste_id)
UNION ALL
SELECT 'user_salles.salle_id', count(*) FROM user_salles us
  WHERE NOT EXISTS (SELECT 1 FROM salles s WHERE s.id = us.salle_id)
UNION ALL
SELECT 'user_matches.concert_id', count(*) FROM user_matches um
  WHERE NOT EXISTS (SELECT 1 FROM concerts c WHERE c.id = um.concert_id);
