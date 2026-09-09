-- V42__evenements_observations.sql
--
-- Refonte du modèle concerts (#1192) : une ligne par source (le modèle actuel) perd de
-- l'information à chaque fusion et nécessitait 3 mécanismes de suppression de doublon distincts
-- (par URL, par salle_id, sans artiste_id), l'un d'eux ayant cassé la production dès son premier
-- jour d'existence (#1187). Sépare désormais l'identité canonique d'un événement réel
-- (`evenements`) des observations rapportées par chaque source (`observations`, une ligne par
-- source par événement, jamais supprimée pour "choisir" une source gagnante).
--
-- `observations.evenement_id, source, url` (NULLS NOT DISTINCT) plutôt que `evenement_id, source`
-- seul : préserve le cas package/normal Ticketmaster (deux observations distinctes de la même
-- source pour le même événement réel), perdu jusqu'ici par suppression de la fiche package.
CREATE TABLE evenements (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    artiste_id              uuid REFERENCES artistes(id) ON DELETE SET NULL,
    artiste_norm            text NOT NULL,
    salle_id                uuid REFERENCES salles(id) ON DELETE SET NULL,
    salle_norm              text NOT NULL,
    salle_cle               text GENERATED ALWAYS AS (regexp_replace(salle_norm, '[^a-z0-9]', '', 'g')) STORED,
    date                    date NOT NULL,
    heure                   time,
    dept                    varchar,
    ville                   text,
    classification          text,
    genre                   text,
    statut                  text,
    disponible              boolean GENERATED ALWAYS AS
        (statut IS NULL OR statut IN ('onsale', 'presale', 'rescheduled', 'postponed')) STORED,
    presale_debut           timestamptz,
    presale_nom             text,
    absences_consecutives   integer NOT NULL DEFAULT 0,
    absence_detectee_le     timestamptz,
    suppression_suggeree_le timestamptz,
    first_seen              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_evenements UNIQUE NULLS NOT DISTINCT (artiste_norm, date, heure, salle_id)
);
CREATE INDEX idx_evenements_artiste_norm ON evenements (artiste_norm);
CREATE INDEX idx_evenements_date ON evenements (date);
CREATE INDEX idx_evenements_dept ON evenements (dept);
CREATE INDEX idx_evenements_disponible ON evenements (disponible);
CREATE INDEX idx_evenements_artiste_norm_trgm ON evenements USING gin (artiste_norm gin_trgm_ops);
CREATE INDEX idx_evenements_salle_id ON evenements (salle_id) WHERE salle_id IS NOT NULL;
CREATE INDEX idx_evenements_artiste_id ON evenements (artiste_id) WHERE artiste_id IS NOT NULL;
CREATE INDEX idx_evenements_salle_cle_trgm ON evenements USING gin (salle_cle gin_trgm_ops);

CREATE TABLE observations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evenement_id   uuid NOT NULL REFERENCES evenements(id) ON DELETE CASCADE,
    source         source_concert NOT NULL,
    url            text,
    artiste_brut   text NOT NULL,
    salle_brut     text NOT NULL,
    first_seen     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_observations UNIQUE NULLS NOT DISTINCT (evenement_id, source, url)
);
CREATE INDEX idx_observations_evenement_id ON observations (evenement_id);
CREATE INDEX idx_observations_url ON observations (source, url, evenement_id) WHERE url IS NOT NULL;
CREATE INDEX idx_observations_artiste_brut_trgm ON observations USING gin (artiste_brut gin_trgm_ops);
CREATE INDEX idx_observations_salle_brut_trgm ON observations USING gin (salle_brut gin_trgm_ops);

-- Migration de données : la contrainte uq_concerts_artiste_date_heure_salle (déjà appliquée avec
-- succès sur les données existantes, migration V37) garantit qu'aucune ligne `concerts` ne partage
-- sa clé avec une autre — chaque ligne devient donc exactement un `evenement` (id conservé tel
-- quel) et exactement une `observation`, sans fusion ni choix de priorité nécessaires ici. Les
-- futures observations multiples pour un même événement (package/normal, sources concurrentes)
-- sont désormais le fait du chemin d'écriture normal, pas de cette migration ponctuelle.
INSERT INTO evenements
    (id, artiste_norm, date, heure, salle_id, salle_norm, ville, dept, classification, genre,
     statut, presale_debut, presale_nom, artiste_id, absences_consecutives, absence_detectee_le,
     suppression_suggeree_le, first_seen, updated_at)
SELECT id, artiste_norm, date, heure, salle_id, salle_norm, ville, dept, classification, genre,
       statut, presale_debut, presale_nom, artiste_id, absences_consecutives, absence_detectee_le,
       suppression_suggeree_le, first_seen, updated_at
FROM concerts;

INSERT INTO observations (evenement_id, source, url, artiste_brut, salle_brut, first_seen, updated_at)
SELECT id, source, url, artiste, salle, first_seen, updated_at
FROM concerts;

-- user_matches.concert_id référence concerts(id) — id conservé à l'identique dans evenements
-- (voir plus haut), toutes les valeurs déjà en base restent donc valides une fois la FK reportée
-- sur evenements. Nom de contrainte retrouvé dynamiquement plutôt que supposé (jamais renommée
-- explicitement depuis sa création V1, mais deviner son nom exact serait fragile).
DO $$
DECLARE
    nom_contrainte text;
BEGIN
    SELECT conname INTO nom_contrainte
    FROM pg_constraint
    WHERE conrelid = 'user_matches'::regclass
      AND confrelid = 'concerts'::regclass
      AND contype = 'f';
    EXECUTE format('ALTER TABLE user_matches DROP CONSTRAINT %I', nom_contrainte);
END $$;
ALTER TABLE user_matches
    ADD CONSTRAINT user_matches_concert_id_fkey
    FOREIGN KEY (concert_id) REFERENCES evenements(id) ON DELETE CASCADE;

-- Compatibilité lecture : les 13 fichiers en lecture seule continuent de lire `concerts` sans
-- modification (vue, pas de réécriture des flux `AccueilService`/`SpectacleService`/etc.).
-- "Meilleure" observation par événement au sens de la même priorité de source que ci-dessus.
-- LEFT JOIN LATERAL plutôt que JOIN : un evenement sans aucune observation (ne devrait jamais
-- survenir via le chemin d'écriture normal, qui crée toujours l'un avec l'autre) reste visible
-- avec des champs d'affichage à NULL plutôt que de disparaître silencieusement de tous les flux de
-- lecture — cohérent avec l'objectif de ne jamais perdre d'information.
DROP TABLE concerts;
CREATE VIEW concerts AS
SELECT
    ev.id, meilleure.artiste_brut AS artiste, ev.artiste_norm, ev.date, ev.heure,
    meilleure.salle_brut AS salle, ev.salle_norm, ev.ville, ev.dept, meilleure.url,
    meilleure.source, ev.classification, ev.genre, ev.statut, ev.disponible,
    ev.first_seen, ev.updated_at, ev.presale_debut, ev.presale_nom, ev.salle_cle,
    ev.salle_id, ev.artiste_id, ev.absences_consecutives, ev.absence_detectee_le,
    ev.suppression_suggeree_le
FROM evenements ev
LEFT JOIN LATERAL (
    SELECT o.artiste_brut, o.salle_brut, o.url, o.source
    FROM observations o
    WHERE o.evenement_id = ev.id
    ORDER BY (CASE WHEN o.url ~* '/pack/' THEN 10 ELSE 0 END
              + CASE o.source::text
                  WHEN 'ticketmaster' THEN 1 WHEN 'eventim' THEN 2 WHEN 'digitick' THEN 3 ELSE 4 END)
    LIMIT 1
) meilleure ON true;
