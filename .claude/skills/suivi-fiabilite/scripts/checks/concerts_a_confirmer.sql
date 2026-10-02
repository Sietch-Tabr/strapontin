-- File « concerts à confirmer » (suppression_suggeree_le), ajoutée au skill
-- le 26/09/2026 — jusqu'ici jamais couverte par aucune section du check
-- périodique, découverte par une revue manuelle hors check (voir #1256,
-- #1261). Chaque ligne vérifiée individuellement (corroboration web : le
-- concert existe-t-il encore réellement à cette date/salle ?) avant de
-- conclure. Plusieurs lignes partageant le même absences_consecutives et la
-- même date de flag signent une cause commune (même salle/source), pas des
-- cas indépendants.
SELECT id, artiste, salle, ville, date, heure, absences_consecutives, suppression_suggeree_le
FROM concerts
WHERE suppression_suggeree_le IS NOT NULL
ORDER BY suppression_suggeree_le DESC;
