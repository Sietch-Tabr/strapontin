-- Un artiste ne porte pas la même classification d'une fiche à l'autre (Ticketmaster classe chaque
-- événement, un humoriste passe en musique selon la salle) et Eventim n'en donne aucune. La vue
-- affiche la classification la plus fréquente parmi toutes les fiches de l'artiste reconnu, à
-- égalité l'ordre alphabétique ; une fiche sans artiste reconnu garde la sienne. La colonne
-- `evenements.classification` reste celle de la source, lue telle quelle par les contrôles du worker.
CREATE OR REPLACE VIEW concerts AS
SELECT
    ev.id, meilleure.artiste_brut AS artiste, ev.artiste_norm, ev.date, ev.heure,
    meilleure.salle_brut AS salle, ev.salle_norm, ev.ville, ev.dept, meilleure.url,
    meilleure.source, COALESCE(majoritaire.classification, ev.classification) AS classification,
    ev.genre, ev.statut, ev.disponible,
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
) meilleure ON true
LEFT JOIN LATERAL (
    SELECT x.classification
    FROM evenements x
    WHERE ev.artiste_id IS NOT NULL AND x.artiste_id = ev.artiste_id
      AND x.classification IS NOT NULL
    GROUP BY x.classification
    ORDER BY count(*) DESC, x.classification
    LIMIT 1
) majoritaire ON true;
