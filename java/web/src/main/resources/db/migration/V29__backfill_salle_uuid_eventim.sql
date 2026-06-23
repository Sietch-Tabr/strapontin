-- V29__backfill_salle_uuid_eventim.sql
-- Backfill rétroactif de salle_uuid pour les concerts Eventim futurs sans liaison salle (#665).
--
-- Assigne salle_uuid aux concerts Eventim (source = 'eventim') dont le champ salle_uuid est
-- NULL et dont le salle_norm correspond de façon non ambiguë à exactement une salle dans la
-- table salles (similarity pg_trgm > 0.6, extension activée en V12).
--
-- La condition COUNT(*) = 1 évite les faux positifs sur les noms génériques ("olympia", etc.)
-- en n'agissant que lorsqu'une seule salle passe le seuil.
-- Les concerts sans salle correspondante (61/90 sans contrepartie TM) ne sont pas modifiés.

UPDATE concerts ec
SET salle_uuid = s.id
FROM salles s
WHERE ec.salle_uuid IS NULL
  AND ec.source = 'eventim'
  AND ec.date >= CURRENT_DATE
  AND similarity(ec.salle_norm, s.nom_norm) > 0.6
  AND (
    SELECT COUNT(*) FROM salles s2
    WHERE similarity(ec.salle_norm, s2.nom_norm) > 0.6
  ) = 1;
