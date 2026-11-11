-- Lieux Eventim rattachés à une salle dont le code postal diffère du leur.
-- Un lieu de billetterie sans rapport avec la salle (rapprochement par simple
-- inclusion de caractères : « vendome » contient « dome ») se reconnaît à son
-- code postal avant de se reconnaître à son nom, la similarité de texte étant
-- trop bruitée (libellés de billetterie très éloignés du nom canonique). Requête
-- figée : toujours utiliser ce fichier tel quel.
--
-- signal :
--   departement_different    : autre département, contamination quasi certaine ;
--   arrondissement_different : Paris, Lyon ou Marseille, autre arrondissement
--                              (cas réels du 09/11/2026 : Dôme de Paris 75015
--                              avec un club du 75002, Les Étoiles 75010 avec un
--                              théâtre du 75011) ;
--   code_postal_voisin       : même département, autre code postal (commune
--                              desservie par plusieurs codes : souvent bénin,
--                              à confirmer par la commune du lieu).
-- fiches_via_ce_lieu : fiches de la salle dont une observation Eventim porte
-- le libellé de ce lieu (les fiches à déplacer en cas de contamination).
WITH lieux AS (
  SELECT ss.salle_id, ss.identifiant_externe, ss.libelle_source,
         coalesce(ss.code_postal, substring(ss.identifiant_externe from '@(\d{5})$')) AS cp_lieu
  FROM salle_sources ss
  WHERE ss.source = 'eventim'
)
SELECT s.id, s.nom, s.ville, s.code_postal AS cp_salle, l.cp_lieu,
  CASE
    WHEN left(l.cp_lieu, 2) <> left(s.code_postal, 2) THEN 'departement_different'
    WHEN s.code_postal ~ '^(750|751|690|691|130|131)' THEN 'arrondissement_different'
    ELSE 'code_postal_voisin'
  END AS signal,
  l.libelle_source, l.identifiant_externe,
  (SELECT count(DISTINCT e.id)
     FROM evenements e JOIN observations o ON o.evenement_id = e.id
    WHERE e.salle_id = s.id AND o.source = 'eventim'
      AND o.salle_brut = l.libelle_source) AS fiches_via_ce_lieu
FROM lieux l
JOIN salles s ON s.id = l.salle_id
WHERE l.cp_lieu IS NOT NULL AND s.code_postal IS NOT NULL AND l.cp_lieu <> s.code_postal
ORDER BY signal, s.nom, l.identifiant_externe;
