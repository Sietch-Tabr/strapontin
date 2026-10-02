-- Rejeu intégral (jamais un échantillon) de toutes les alertes
-- logs_admin source='coherence-wikidata' accumulées, groupées par message
-- distinct. Un total stable ne prouve rien sur les identités individuelles
-- (voir HISTORIQUE.md) — chaque ligne listée doit être confrontée à
-- coherence_wikidata_dept_ville.sql et/ou vérifiée individuellement contre
-- concerts.ville avant de la classer "déjà connue".
SELECT message, count(*) AS nb_nuits,
  min(ts) AS premiere_occurrence,
  max(ts) AS derniere_occurrence
FROM logs_admin
WHERE source = 'coherence-wikidata'
GROUP BY message
ORDER BY message;
