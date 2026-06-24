-- V30__backfill_salle_sources_eventim.sql
-- Backfill de salle_sources pour les salles Eventim sans identifiant externe (#689).
--
-- Eventim n'expose pas de venueId stable dans son API. Les salles créées via les
-- concerts Eventim n'ont donc aucune entrée dans salle_sources, ce qui les fait
-- apparaître dans la liste admin "Sans source" (destinée à la future source Digitick).
--
-- Génère un identifiant interne "internal:<nom_norm>" pour chaque salle ayant des
-- concerts futurs Eventim mais aucune entrée Eventim dans salle_sources.
-- Écrasable par un vrai venueId si Eventim en expose un à l'avenir (ON CONFLICT DO NOTHING).

INSERT INTO salle_sources (salle_id, source, identifiant_externe)
SELECT s.id, 'eventim', 'internal:' || s.nom_norm
FROM salles s
WHERE NOT EXISTS (
    SELECT 1 FROM salle_sources ss
    WHERE ss.salle_id = s.id AND ss.source = 'eventim'
)
AND EXISTS (
    SELECT 1 FROM concerts c
    WHERE c.salle_uuid = s.id AND c.source = 'eventim' AND c.date >= CURRENT_DATE
)
ON CONFLICT DO NOTHING;
