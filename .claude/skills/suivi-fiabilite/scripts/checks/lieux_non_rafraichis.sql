-- Lieux dont toutes les fiches à venir d'une seule source ne sont plus revues
-- depuis le délai de rafraîchissement par lieu (Ticketmaster 7 jours, Eventim
-- 3 jours : FetchSallesExecutor.JOURS_AVANT_RAFRAICHISSEMENT_*), dans un
-- département suivi. Requête figée : toujours utiliser ce fichier tel quel.
--
-- fiches_sous_seuil : fiches encore sous SEUIL_ABSENCES_CONSECUTIVES (5). Le
-- lieu n'est réinterrogé que si ce nombre est non nul (CONDITION_SALLE_A_RAFRAICHIR) :
-- 0 avec des fiches non revues = lieu jamais réinterrogé, ses fiches dérivent vers
-- les 50 absences qui les signalent à supprimer (cas réel du 09/11/2026 : Marigny,
-- Gaîté-Montparnasse, Madeleine pour Ticketmaster).
-- Eventim : la requête par lieu compte elle-même les absences, un lieu à 0 fiche
-- sous le seuil y est le plus souvent normal (produits réellement retirés). Le
-- signal porte surtout sur Ticketmaster, que fetch:region ne revoit pas pour le
-- théâtre et l'humour.
-- Toute ligne est à confirmer sur le site officiel du lieu avant de conclure que
-- les fiches sont périmées ou que la source ne les renvoie plus.
WITH fiches AS (
  SELECT e.id, e.salle_id, e.date, e.absences_consecutives,
         max(o.updated_at) AS derniere_obs,
         CASE WHEN bool_and(o.source = 'ticketmaster') THEN 'ticketmaster'
              WHEN bool_and(o.source = 'eventim') THEN 'eventim' END AS source_unique
  FROM evenements e
  JOIN observations o ON o.evenement_id = e.id
  WHERE e.date >= current_date
    AND EXISTS (SELECT 1 FROM user_config uc WHERE e.dept = ANY (uc.departements))
  GROUP BY e.id, e.salle_id, e.date, e.absences_consecutives
)
SELECT s.nom, f.source_unique AS source, count(*) AS fiches_non_revues,
  count(*) FILTER (WHERE f.absences_consecutives < 5) AS fiches_sous_seuil,
  min(f.absences_consecutives) AS absences_min, max(f.absences_consecutives) AS absences_max,
  max(f.derniere_obs)::date AS derniere_observation,
  min(f.date) AS premiere_date, max(f.date) AS derniere_date,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM user_matches um WHERE um.concert_id = f.id)) AS fiches_avec_match
FROM fiches f
JOIN salles s ON s.id = f.salle_id
WHERE f.source_unique IS NOT NULL
  AND f.derniere_obs < now() - (CASE f.source_unique WHEN 'ticketmaster' THEN interval '7 days'
                                                       ELSE interval '3 days' END)
GROUP BY s.nom, f.source_unique
ORDER BY count(*) DESC, s.nom;
