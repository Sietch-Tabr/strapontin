-- V30__supprimer_salles_cp_morte.sql
--
-- salles.cp (V26) répliquait l'ajout de colonne du web V34 sans jamais appliquer le renommage
-- cp -> dept du web V35 — le worker possédait déjà salles.dept depuis sa propre V12. Colonne
-- morte dès sa création (aucune référence dans worker/src/main ni web/src/main). Sans impact
-- prod (spring.flyway.enabled=false côté worker).

ALTER TABLE salles DROP COLUMN cp;
