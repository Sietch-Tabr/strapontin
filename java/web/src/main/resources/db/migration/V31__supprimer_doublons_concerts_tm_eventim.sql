-- V31__supprimer_doublons_concerts_tm_eventim.sql
-- Suppression des doublons TM+Eventim non fusionnés par reconcilierSalles (#690).
--
-- 7 concerts existent en double (source TM + source Eventim pour le même événement)
-- car les salle_norm diffèrent (ex. "zenith d auvergne" vs "zenith dauvergne clermont ferrand").
-- Après résolution UUID-first, ces concerts partagent le même artiste_id + salle_uuid + date + heure
-- mais la contrainte UNIQUE de concerts est sur (artiste_norm, date, heure, salle_norm),
-- donc les deux lignes coexistent.
--
-- Garde la ligne TM (source de référence), supprime la ligne Eventim.

DELETE FROM concerts
WHERE id IN (
    SELECT c2.id
    FROM concerts c1
    JOIN concerts c2
        ON c1.artiste_id = c2.artiste_id
        AND c1.date = c2.date
        AND c1.heure IS NOT DISTINCT FROM c2.heure
        AND c1.salle_uuid = c2.salle_uuid
    WHERE c1.source = 'ticketmaster'
        AND c2.source = 'eventim'
        AND c1.artiste_id IS NOT NULL
        AND c1.salle_uuid IS NOT NULL
);
