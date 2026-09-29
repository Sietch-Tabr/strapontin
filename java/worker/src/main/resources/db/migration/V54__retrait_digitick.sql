-- V54__retrait_digitick.sql
--
-- Digitick retirée des sources (plus aucune salle exploitable, voir wiki Sources-de-données).
-- La valeur 'digitick' de l'enum source_concert reste : PostgreSQL ne sait pas retirer une valeur
-- d'enum. Plus aucun fetch n'étant possible, le mapping salle_sources est supprimé ; une
-- observation Digitick redondante (événement déjà observé par une autre source) aussi. Une
-- observation Digitick seule est conservée pour que l'événement reste affiché : elle passe
-- derrière toute observation Ticketmaster/Eventim à venir (priorité de la vue concerts) et
-- disparaît avec l'événement une fois passé.

DELETE FROM observations o
WHERE o.source = 'digitick'
  AND EXISTS (
      SELECT 1 FROM observations autre
      WHERE autre.evenement_id = o.evenement_id AND autre.source <> 'digitick');

DELETE FROM salle_sources WHERE source = 'digitick';
DELETE FROM artiste_sources WHERE source = 'digitick';
