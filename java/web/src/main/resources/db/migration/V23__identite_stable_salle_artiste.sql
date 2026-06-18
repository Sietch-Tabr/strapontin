-- V23__identite_stable_salle_artiste.sql
-- Identité stable salle/artiste : UUID internes + tables de correspondance inter-sources (ticket #621).
--
-- Migration additive : aucune colonne existante modifiée ni supprimée.
-- Les colonnes UUID sont nullable jusqu'au backfill supervisé (scripts/backfill-621.sql).
-- Le renommage salles.salle_uuid → salles.id (PK uuid) est réservé au ticket de nettoyage post-backfill.

-- UUID stable sur chaque salle (DEFAULT garantit un UUID pour les lignes existantes)
ALTER TABLE salles ADD COLUMN salle_uuid uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE salles ADD CONSTRAINT salles_salle_uuid_key UNIQUE (salle_uuid);

-- Table artistes (n'existait pas — artistes distincts, partagés entre utilisateurs)
CREATE TABLE artistes (
    id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nom      text NOT NULL,
    nom_norm text NOT NULL,
    CONSTRAINT uq_artistes_nom_norm UNIQUE (nom_norm)
);

-- Correspondance source externe → salle interne
-- PK (source, identifiant_externe) garantit qu'un ID externe est unique par source.
-- source en texte libre (pas l'enum source_concert) pour rester ouvert à Digitick sans dépendre de #610.
CREATE TABLE salle_sources (
    salle_id            uuid NOT NULL REFERENCES salles(salle_uuid) ON DELETE CASCADE,
    source              text NOT NULL,
    identifiant_externe text NOT NULL,
    PRIMARY KEY (source, identifiant_externe)
);
CREATE INDEX idx_salle_sources_salle_id ON salle_sources (salle_id);

-- Correspondance source externe → artiste interne
CREATE TABLE artiste_sources (
    artiste_id          uuid NOT NULL REFERENCES artistes(id) ON DELETE CASCADE,
    source              text NOT NULL,
    identifiant_externe text NOT NULL,
    PRIMARY KEY (source, identifiant_externe)
);
CREATE INDEX idx_artiste_sources_artiste_id ON artiste_sources (artiste_id);

-- Colonnes UUID sur concerts (remplies progressivement par PersisterConcerts après migration)
ALTER TABLE concerts
    ADD COLUMN salle_uuid uuid REFERENCES salles(salle_uuid) ON DELETE SET NULL,
    ADD COLUMN artiste_id uuid REFERENCES artistes(id)       ON DELETE SET NULL;

-- Colonne artiste_id sur user_artistes (remplie lors de ArtisteService.ajouter + backfill)
ALTER TABLE user_artistes
    ADD COLUMN artiste_id uuid REFERENCES artistes(id) ON DELETE SET NULL;

-- Colonne salle_uuid sur user_salles (remplie lors de SalleService.ajouter + backfill)
ALTER TABLE user_salles
    ADD COLUMN salle_uuid uuid REFERENCES salles(salle_uuid) ON DELETE CASCADE;

-- Index partiels (WHERE NOT NULL) pour éviter de polluer l'index avec les NULL de la transition
CREATE INDEX idx_concerts_salle_uuid      ON concerts      (salle_uuid) WHERE salle_uuid IS NOT NULL;
CREATE INDEX idx_concerts_artiste_id      ON concerts      (artiste_id) WHERE artiste_id IS NOT NULL;
CREATE INDEX idx_user_artistes_artiste_id ON user_artistes (artiste_id) WHERE artiste_id IS NOT NULL;
CREATE INDEX idx_user_salles_salle_uuid   ON user_salles   (salle_uuid) WHERE salle_uuid IS NOT NULL;
