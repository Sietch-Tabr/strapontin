-- Signalements de matching actifs, mêmes 4 sources agrégées par l'onglet
-- admin « À traiter » (SignalementsAdminService.lister) — pas seulement
-- signalements_matching (jalon 2.9.0, ControleIntegriteService, cycle
-- nocturne 3h : collision wikidata_id, ambiguïté Wikidata sur découverte
-- artiste, réattribution TM, source_orpheline). Les 3 mécanismes non migrés
-- (necessite_intervention_le, contamination_signalee, doublon_signale)
-- n'apparaissent dans aucun logs_admin récent une fois posés une première
-- fois (CoherenceMultiSalleService ne re-logue que si la signature change,
-- jamais à l'identique chaque nuit) — un check limité aux logs_admin de la
-- fenêtre du check les manque intégralement s'ils ont été posés avant cette
-- fenêtre (trouvé le 26/09/2026 : 29 signalements salle actifs, certains
-- depuis plusieurs semaines, jamais vus par ce script avant sa réécriture).
-- État courant, indépendant de toute fenêtre temporelle — un signalement
-- non résolu (resolu_le IS NULL) est un candidat direct à investiguer.
-- V72 (jalon 3.0.1, 29/09/2026) : doublon_signale devient uuid (FK vers
-- salles.id, ON DELETE SET NULL) — cast ::text explicite ici, sinon "les
-- UNION types text et uuid ne peuvent pas correspondre" (script resté
-- cassé en silence tant que personne ne relit l'erreur brute de psql).
-- Même migration : décisions admin persistées désormais dans
-- salle_sources.contamination_acceptee_le et la table
-- salles_paires_distinctes — pas encore agrégées ici, à envisager si ces
-- mécanismes se peuplent (vérifier leur usage réel avant de les ajouter).
SELECT 'nouveau' AS categorie, sm.type, sm.entite_type, sm.entite_id::text AS entite_id,
       sm.confiance, sm.detecte_le, sm.details::text AS details
FROM signalements_matching sm
WHERE sm.resolu_le IS NULL
UNION ALL
SELECT 'necessite_intervention', 'necessite_intervention', 'artiste', a.artiste_id::text,
       'moyenne', a.necessite_intervention_le, NULL
FROM artiste_sources a
WHERE a.necessite_intervention_le IS NOT NULL
UNION ALL
SELECT 'contamination_salle', 'contamination_salle', 'salle', s.id::text,
       'moyenne', NULL, s.contamination_signalee
FROM salles s
WHERE s.contamination_signalee IS NOT NULL
UNION ALL
SELECT 'doublon_salle', 'doublon_salle', 'salle', s.id::text,
       'moyenne', NULL, s.doublon_signale::text
FROM salles s
WHERE s.doublon_signale IS NOT NULL
ORDER BY detecte_le NULLS LAST;
