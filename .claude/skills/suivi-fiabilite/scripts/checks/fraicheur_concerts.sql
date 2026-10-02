-- Concerts suivis (artiste ou salle) non retouchés depuis 14j, groupés par
-- artiste_norm. PersisteurConcertsDb pose updated_at=now() sans condition à
-- chaque upsert réussi — non rafraîchi depuis 14j signifie littéralement
-- absent de toute réponse source depuis 14j, jamais "rien n'a changé" sans
-- preuve. jamais_retouche=true (updated_at=first_seen) est un signal fort.
SELECT c.artiste_norm, count(*) AS nb_concerts,
  min(c.updated_at) AS plus_ancien_maj,
  bool_and(c.updated_at = c.first_seen) AS jamais_retouche
FROM concerts c
WHERE c.date >= current_date AND c.updated_at < now() - interval '14 days'
  AND (EXISTS (SELECT 1 FROM user_artistes ua WHERE ua.artiste_id = c.artiste_id)
       OR EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = c.salle_id))
GROUP BY c.artiste_norm
ORDER BY nb_concerts DESC;
