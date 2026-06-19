-- Nettoyage des doublons de salles
-- Détecte dynamiquement les doublons par nom (exact puis accents),
-- choisit le canonique (plus de sources > plus d'utilisateurs > plus de concerts > UUID),
-- fusionne concerts/user_salles/salle_sources, supprime les orphelins.
-- La suppression finale dans `salles` déclenche ON DELETE CASCADE sur user_salles et salle_sources.
-- Exécuter sur prod et dev : su - postgres -c "psql -d strapontin -v ON_ERROR_STOP=1 -f fix-doublons-salles.sql"

BEGIN;

-- ── 0. Correspondances orphelin → canonique (calculées avant toute modification) ──
CREATE TEMP TABLE fusion_salles AS
WITH rang_salles AS (
  SELECT
    s.salle_uuid,
    s.nom,
    ROW_NUMBER() OVER (
      PARTITION BY s.nom
      ORDER BY
        COUNT(DISTINCT ss.identifiant_externe) DESC,
        COUNT(DISTINCT us.user_id)             DESC,
        COUNT(DISTINCT c.id)                   DESC,
        s.salle_uuid
    ) AS rang
  FROM salles s
  LEFT JOIN salle_sources ss ON ss.salle_id   = s.salle_uuid
  LEFT JOIN user_salles   us ON us.salle_uuid = s.salle_uuid
  LEFT JOIN concerts       c ON  c.salle_uuid = s.salle_uuid
  WHERE s.nom IN (SELECT nom FROM salles GROUP BY nom HAVING COUNT(*) > 1)
  GROUP BY s.salle_uuid, s.nom
)
SELECT o.salle_uuid AS uuid_orphelin, k.salle_uuid AS uuid_canonique, k.nom AS nom_canonique
FROM rang_salles o
JOIN rang_salles k ON k.nom = o.nom AND k.rang = 1
WHERE o.rang > 1;

SELECT 'Doublons nom exact' AS phase, count(*) AS nb FROM fusion_salles;
SELECT nom_canonique, uuid_orphelin, uuid_canonique FROM fusion_salles ORDER BY nom_canonique;

-- ── 1. Fusion concerts ────────────────────────────────────────────────────────
UPDATE concerts SET salle_uuid = f.uuid_canonique
FROM fusion_salles f WHERE concerts.salle_uuid = f.uuid_orphelin;

-- ── 2. Fusion salle_sources (ex. Stade de France : 2 TM ids → même canonique) ─
UPDATE salle_sources SET salle_id = f.uuid_canonique
FROM fusion_salles f
WHERE salle_sources.salle_id = f.uuid_orphelin
  AND NOT EXISTS (
    SELECT 1 FROM salle_sources e
    WHERE e.salle_id             = f.uuid_canonique
      AND e.source               = salle_sources.source
      AND e.identifiant_externe  = salle_sources.identifiant_externe
  );

-- ── 3. Migration user_salles : les utilisateurs de l'orphelin suivent le canonique ──
-- salle_id (texte, PK legacy) est encore NOT NULL → doit être fourni.
INSERT INTO user_salles (user_id, salle_id, salle_uuid)
SELECT us.user_id, s_canon.id, f.uuid_canonique
FROM user_salles us
JOIN fusion_salles f      ON f.uuid_orphelin       = us.salle_uuid
JOIN salles        s_canon ON s_canon.salle_uuid   = f.uuid_canonique
ON CONFLICT (user_id, salle_id) DO NOTHING;

-- ── 4. Suppression des orphelins (CASCADE nettoie user_salles + salle_sources) ──
DELETE FROM salles WHERE salle_uuid IN (SELECT uuid_orphelin FROM fusion_salles);

-- ── 5. Variants d'accent (ex. "Elysee Montmartre" ↔ "Elysée Montmartre") ──────
-- TM supprime les accents ; les noms normalisés sont identiques mais les noms bruts diffèrent.
CREATE TEMP TABLE fusion_accents AS
WITH rang_accents AS (
  SELECT
    s.salle_uuid,
    s.nom,
    unaccent(lower(s.nom)) AS nom_norm,
    ROW_NUMBER() OVER (
      PARTITION BY unaccent(lower(s.nom))
      ORDER BY
        COUNT(DISTINCT ss.identifiant_externe) DESC,
        COUNT(DISTINCT us.user_id)             DESC,
        COUNT(DISTINCT c.id)                   DESC,
        s.salle_uuid
    ) AS rang
  FROM salles s
  LEFT JOIN salle_sources ss ON ss.salle_id   = s.salle_uuid
  LEFT JOIN user_salles   us ON us.salle_uuid = s.salle_uuid
  LEFT JOIN concerts       c ON  c.salle_uuid = s.salle_uuid
  WHERE unaccent(lower(s.nom)) IN (
    SELECT unaccent(lower(nom))
    FROM salles
    GROUP BY unaccent(lower(nom))
    HAVING COUNT(*) > 1 AND bool_or(nom <> unaccent(nom))
  )
  GROUP BY s.salle_uuid, s.nom
)
SELECT o.salle_uuid AS uuid_orphelin, k.salle_uuid AS uuid_canonique, k.nom AS nom_canonique
FROM rang_accents o
JOIN rang_accents k ON k.nom_norm = o.nom_norm AND k.rang = 1
WHERE o.rang > 1;

SELECT 'Doublons accent' AS phase, count(*) AS nb FROM fusion_accents;
SELECT nom_canonique, uuid_orphelin, uuid_canonique FROM fusion_accents ORDER BY nom_canonique;

UPDATE concerts SET salle_uuid = f.uuid_canonique
FROM fusion_accents f WHERE concerts.salle_uuid = f.uuid_orphelin;

UPDATE salle_sources SET salle_id = f.uuid_canonique
FROM fusion_accents f
WHERE salle_sources.salle_id = f.uuid_orphelin
  AND NOT EXISTS (
    SELECT 1 FROM salle_sources e
    WHERE e.salle_id            = f.uuid_canonique
      AND e.source              = salle_sources.source
      AND e.identifiant_externe = salle_sources.identifiant_externe
  );

INSERT INTO user_salles (user_id, salle_id, salle_uuid)
SELECT us.user_id, s_canon.id, f.uuid_canonique
FROM user_salles us
JOIN fusion_accents f      ON f.uuid_orphelin       = us.salle_uuid
JOIN salles         s_canon ON s_canon.salle_uuid   = f.uuid_canonique
ON CONFLICT (user_id, salle_id) DO NOTHING;

DELETE FROM salles WHERE salle_uuid IN (SELECT uuid_orphelin FROM fusion_accents);

-- ── 6. Injection IDs TM manquants ─────────────────────────────────────────────
INSERT INTO salle_sources (salle_id, source, identifiant_externe)
SELECT s.salle_uuid, 'ticketmaster', 'rZFSnyZe71v'
FROM salles s WHERE s.nom = 'Bourse du Travail Lyon'
ON CONFLICT (source, identifiant_externe) DO UPDATE SET salle_id = EXCLUDED.salle_id;

INSERT INTO salle_sources (salle_id, source, identifiant_externe)
SELECT s.salle_uuid, 'ticketmaster', 'rZ7SnyZaAGI'
FROM salles s WHERE s.nom IN ('Paris la Defense Arena', 'Paris La Défense Arena')
ON CONFLICT (source, identifiant_externe) DO UPDATE SET salle_id = EXCLUDED.salle_id;

-- ── 7. Corrections de noms ────────────────────────────────────────────────────
UPDATE salles SET nom = 'Elysée Montmartre'      WHERE nom = 'Elysee Montmartre';
UPDATE salles SET nom = 'Opéra Bastille'          WHERE nom = 'Opera Bastille';
UPDATE salles SET nom = 'Théâtre Mogador'         WHERE nom = 'Theatre Mogador';

-- ── 8. Vérification finale ────────────────────────────────────────────────────
SELECT 'Doublons restants' AS verification, COUNT(*) AS nb
FROM (SELECT nom FROM salles GROUP BY nom HAVING COUNT(*) > 1) t;

COMMIT;
