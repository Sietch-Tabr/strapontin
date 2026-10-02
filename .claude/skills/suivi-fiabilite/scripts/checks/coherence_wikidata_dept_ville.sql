-- Salles enrichies (wikidata_id non nul) dont la ville stockée ne correspond
-- à aucune ville réelle de concert rattachée (containment texte, insensible
-- accents/casse). Complémentaire à la table logs_admin source='coherence-wikidata'
-- (qui ne compare qu'aux communeWikidata, peut afficher ville=communeWikidata
-- identiques tout en cachant une vraie divergence contre concerts.ville — cf.
-- HISTORIQUE.md, cas "Espace Saint-Exupéry" du 06/08/2026 découvert par CETTE
-- requête et non par la liste coherence-wikidata seule).
--
-- ⚠️ Fort taux de faux positifs sur les abréviations administratives
-- (Saint/St, arrondissements, EPCI/communes nouvelles) — chaque ligne
-- retournée doit être vérifiée individuellement (dept cohérent des deux
-- côtés = signal fiable ; recherche Wikidata/web si dept aussi incohérent),
-- jamais classée en bloc par catégorie apparente.
SELECT s.id, s.nom, s.ville AS ville_stockee, s.dept AS dept_stocke, s.wikidata_id,
  array_agg(DISTINCT c.ville) AS villes_concerts_reels,
  array_agg(DISTINCT c.dept) AS depts_concerts_reels
FROM salles s
JOIN concerts c ON c.salle_id = s.id
WHERE s.wikidata_id IS NOT NULL AND s.ville IS NOT NULL
GROUP BY s.id, s.nom, s.ville, s.dept, s.wikidata_id
HAVING NOT EXISTS (
  SELECT 1 FROM unnest(array_agg(DISTINCT c.ville)) v
  WHERE unaccent(lower(v)) LIKE '%' || unaccent(lower(s.ville)) || '%'
     OR unaccent(lower(s.ville)) LIKE '%' || unaccent(lower(v)) || '%'
)
ORDER BY s.nom;
