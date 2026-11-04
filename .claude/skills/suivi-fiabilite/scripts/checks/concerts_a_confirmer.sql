-- File « concerts à confirmer » (suppression_suggeree_le). Chaque ligne se vérifie
-- individuellement (site officiel de la salle d'abord). Colonnes de tri :
--   matches    : utilisateurs concernés (une suppression les supprime aussi) ;
--   observations : source:dernière mise à jour, une source qui n'est plus renvoyée
--                  depuis des semaines signe un produit retiré ;
--   jumelles   : autres fiches à venir de la même salle, même artiste ou même jour
--                (id8 date heure absences) : une jumelle rafraîchie à une autre date
--                ou heure signe un produit republié, l'ancienne fiche est un fantôme.
-- Plusieurs lignes avec les mêmes absences et la même date de flag signent une cause commune.
SELECT left(e.id::text, 8) AS id8,
       e.artiste_norm AS artiste,
       s.nom AS salle,
       e.date,
       e.heure,
       e.absences_consecutives AS absences,
       e.suppression_suggeree_le::date AS flague_le,
       (SELECT count(*) FROM user_matches m WHERE m.concert_id = e.id) AS matches,
       (SELECT string_agg(o.source::text || ':' || to_char(o.updated_at, 'DD/MM'), ',' ORDER BY o.source::text)
          FROM observations o WHERE o.evenement_id = e.id) AS observations,
       (SELECT string_agg(left(j.id::text, 8) || ' ' || j.date || ' ' || coalesce(j.heure::text, 'sans heure')
                          || ' abs' || j.absences_consecutives, ' | ' ORDER BY j.date)
          FROM evenements j
          WHERE j.salle_id = e.salle_id AND j.id <> e.id AND j.date >= CURRENT_DATE
            AND j.suppression_suggeree_le IS NULL
            AND (j.artiste_norm = e.artiste_norm OR j.date = e.date)
            AND similarity(j.artiste_norm, e.artiste_norm) > 0.4) AS jumelles
FROM evenements e
LEFT JOIN salles s ON s.id = e.salle_id
WHERE e.suppression_suggeree_le IS NOT NULL
ORDER BY e.suppression_suggeree_le DESC, e.date;
