-- V79__remise_a_zero_absences_hors_recherche.sql
--
-- Spectacles Ticketmaster (humour, théâtre, jeunesse…) entrés par une salle autrefois suivie :
-- la recherche par département, limitée à la musique, l'opéra et la comédie musicale, ne pouvait
-- pas les renvoyer et leur comptait une absence par nuit, jusqu'à les proposer à la suppression
-- alors qu'ils sont toujours programmés (298 en production au 3 octobre 2026). Depuis V78, elle ne
-- compte plus d'absence hors des classements qu'elle renvoie ; les compteurs déjà faussés repartent
-- de zéro. Les événements d'un artiste ou d'une salle suivis gardent les leurs : d'autres
-- récupérations, sans ce filtre, les ont réellement cherchés.
UPDATE evenements e
SET absences_consecutives = 0,
    absence_detectee_le = NULL,
    suppression_suggeree_le = NULL
WHERE e.date >= CURRENT_DATE
  AND e.absences_consecutives > 0
  AND EXISTS (SELECT 1 FROM observations o
              WHERE o.evenement_id = e.id AND o.source = 'ticketmaster')
  AND NOT (coalesce(e.classification, '') = 'music' OR e.genre IN ('Opéra', 'Musique'))
  AND NOT EXISTS (SELECT 1 FROM user_artistes ua WHERE ua.artiste_id = e.artiste_id)
  AND NOT EXISTS (SELECT 1 FROM user_salles us WHERE us.salle_id = e.salle_id);
