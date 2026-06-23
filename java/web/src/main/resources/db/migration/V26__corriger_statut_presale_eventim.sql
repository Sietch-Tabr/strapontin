-- Corrige les concerts Eventim stockés à tort avec statut='presale' :
-- l'API Eventim ne distingue pas les préventes des concerts complets (inStock=false).
UPDATE concerts
SET statut = NULL
WHERE source = 'eventim'
  AND statut = 'presale'
  AND presale_debut IS NULL;
