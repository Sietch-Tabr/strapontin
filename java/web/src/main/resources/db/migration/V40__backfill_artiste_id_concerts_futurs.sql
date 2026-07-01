-- Backfill artiste_id pour les concerts futurs sans artiste_id
-- Couvre Digitick (jamais branché sur la résolution artiste) et Eventim partiels.
-- Correspondance exacte artiste_norm = artistes.nom_norm.
UPDATE concerts c
SET artiste_id = a.id,
    updated_at = now()
FROM artistes a
WHERE c.artiste_id IS NULL
  AND c.date >= CURRENT_DATE
  AND c.artiste_norm = a.nom_norm;
