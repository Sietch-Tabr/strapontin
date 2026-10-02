-- Doublons de salle par salle_norm (texte source brut identique/normalisé)
-- portant sur plusieurs salle_id distincts, concerts futurs uniquement.
SELECT salle_norm, count(DISTINCT salle_id) AS nb_uuid, array_agg(DISTINCT salle_id) AS salle_ids
FROM concerts
WHERE salle_id IS NOT NULL AND date >= current_date
GROUP BY salle_norm
HAVING count(DISTINCT salle_id) > 1
ORDER BY salle_norm;
