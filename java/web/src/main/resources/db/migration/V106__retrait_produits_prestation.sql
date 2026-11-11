-- V106__retrait_produits_prestation.sql
--
-- Un produit de prestation, d'hospitalité ou d'accès prioritaire n'est pas une séance : ses
-- observations sont retirées, puis les événements qu'elles étaient seules à porter (matches
-- compris, par cascade). Le concert réel revient avec son produit de billetterie ordinaire.
WITH retirees AS (
    DELETE FROM observations
    WHERE lower(url) ~ 'prestation|hospitalit|priority-(pass|access)'
    RETURNING evenement_id
)
DELETE FROM evenements e
WHERE e.id IN (SELECT evenement_id FROM retirees)
  AND NOT EXISTS (SELECT 1 FROM observations o WHERE o.evenement_id = e.id);
