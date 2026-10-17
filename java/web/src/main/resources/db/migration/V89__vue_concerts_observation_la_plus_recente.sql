-- La vue `concerts` départageait deux observations de même source (et même nature, package ou non)
-- au hasard : une fiche périmée, republiée ou non revue depuis des jours, pouvait l'emporter sur la
-- plus récemment confirmée et afficher le nom de salle d'une ancienne salle. À égalité de priorité,
-- l'observation la plus récemment confirmée est retenue, puis la plus ancienne vue, puis l'id
-- (ordre total, aucun résultat dépendant de l'ordre physique des lignes).
CREATE OR REPLACE VIEW concerts AS
SELECT
    ev.id, meilleure.artiste_brut AS artiste, ev.artiste_norm, ev.date, ev.heure,
    meilleure.salle_brut AS salle, ev.salle_norm, ev.ville, ev.dept, meilleure.url,
    meilleure.source, ev.classification, ev.genre, ev.statut, ev.disponible,
    ev.first_seen, ev.updated_at, ev.presale_debut, ev.presale_nom, ev.salle_cle,
    ev.salle_id, ev.artiste_id, ev.absences_consecutives, ev.absence_detectee_le,
    ev.suppression_suggeree_le
FROM evenements ev
LEFT JOIN LATERAL (
    SELECT o.artiste_brut, o.salle_brut, o.url, o.source
    FROM observations o
    WHERE o.evenement_id = ev.id
    ORDER BY (CASE WHEN o.url ~* '/pack/' THEN 10 ELSE 0 END
              + CASE o.source::text
                  WHEN 'ticketmaster' THEN 1 WHEN 'eventim' THEN 2 WHEN 'digitick' THEN 3 ELSE 4 END),
             o.updated_at DESC, o.first_seen, o.id
    LIMIT 1
) meilleure ON true;
