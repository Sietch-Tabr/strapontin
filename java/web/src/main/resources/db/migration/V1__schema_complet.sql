-- V1__schema_complet.sql
-- Schéma initial Strapontin v2.0.0
-- Incorpore toutes les corrections #A-#J :
--   #A : idx_user_matches_non_vus WHERE notifie_le IS NULL (était vu_le)
--   #B : PK composite sur user_artistes + user_matches
--   #C : table salles + refonte user_salles
--   #D : disponible GENERATED ALWAYS AS (statut IS NULL OR statut = 'onsale')
--   #E : fetch_jobs en V4
--   #F : accounts sans colonnes OAuth inutilisées
--   #G : users sans image
--   #H : user_matches sans vu_le
--   #I : concerts.source sans 'bandsintown'
--   #J : types ENUM à la place de text + CHECK

-- ---------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS unaccent;

-- ---------------------------------------------------------------
-- Types ENUM (#J)
-- ---------------------------------------------------------------

CREATE TYPE source_concert      AS ENUM ('ticketmaster', 'eventim');
CREATE TYPE role_utilisateur    AS ENUM ('user', 'admin');
CREATE TYPE frequence_notif     AS ENUM ('jamais', 'temps_reel', 'quotidien');
CREATE TYPE theme_interface     AS ENUM ('auto', 'clair', 'sombre');

-- ---------------------------------------------------------------
-- users (#G : sans image — #J : ENUM role)
-- ---------------------------------------------------------------

CREATE TABLE users (
    id                       text                     PRIMARY KEY NOT NULL,
    name                     text                     NOT NULL,
    email                    text                     NOT NULL,
    email_verified           boolean                  DEFAULT false NOT NULL,
    role                     role_utilisateur          DEFAULT 'user' NOT NULL,
    username                 text,
    display_username         text,
    two_factor_enabled       boolean                  DEFAULT false NOT NULL,
    must_change_credentials  boolean                  DEFAULT false NOT NULL,
    created_at               timestamp with time zone DEFAULT now() NOT NULL,
    updated_at               timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- ---------------------------------------------------------------
-- accounts (#F : sans colonnes OAuth — password uniquement)
-- ---------------------------------------------------------------

CREATE TABLE accounts (
    id          text                     PRIMARY KEY NOT NULL,
    account_id  text                     NOT NULL,
    provider_id text                     NOT NULL,
    user_id     text                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password    text,
    created_at  timestamp with time zone NOT NULL,
    updated_at  timestamp with time zone NOT NULL
);

-- ---------------------------------------------------------------
-- app_config
-- ---------------------------------------------------------------

CREATE TABLE app_config (
    cle            text                     PRIMARY KEY NOT NULL,
    valeur_chiffree text                    NOT NULL,
    mis_a_jour_le  timestamp with time zone DEFAULT now() NOT NULL
);

-- ---------------------------------------------------------------
-- concerts (#D : disponible généré — #I : sans bandsintown — #J : ENUM source)
-- ---------------------------------------------------------------

CREATE TABLE concerts (
    id             uuid                     PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    artiste        text                     NOT NULL,
    artiste_norm   text                     NOT NULL,
    date           date                     NOT NULL,
    heure          time,
    salle          text                     NOT NULL,
    salle_norm     text                     NOT NULL,
    ville          text,
    dept           varchar(3),
    prix_min       numeric(8, 2),
    prix_max       numeric(8, 2),
    url            text,
    source         source_concert           NOT NULL,
    classification text,
    genre          text,
    statut         text,
    disponible     boolean GENERATED ALWAYS AS (statut IS NULL OR statut = 'onsale') STORED,
    first_seen     timestamp with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_concerts_artiste_date_salle UNIQUE (artiste_norm, date, salle_norm)
);

CREATE INDEX idx_concerts_artiste_norm ON concerts USING btree (artiste_norm);
CREATE INDEX idx_concerts_date        ON concerts USING btree (date);
CREATE INDEX idx_concerts_dept        ON concerts USING btree (dept);
CREATE INDEX idx_concerts_disponible  ON concerts USING btree (disponible);
CREATE INDEX idx_concerts_source      ON concerts USING btree (source);

-- ---------------------------------------------------------------
-- salles (NEW — correction #C)
-- id = salle_norm, sert de clé naturelle stable
-- ---------------------------------------------------------------

CREATE TABLE salles (
    id       text PRIMARY KEY NOT NULL,
    nom      text NOT NULL,
    nom_norm text NOT NULL
);

-- ---------------------------------------------------------------
-- push_abonnements
-- ---------------------------------------------------------------

CREATE TABLE push_abonnements (
    id        uuid                     PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    user_id   text                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint  text                     NOT NULL,
    p256dh    text                     NOT NULL,
    auth      text                     NOT NULL,
    cree_le   timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_push_user_endpoint UNIQUE (user_id, endpoint)
);

CREATE INDEX idx_push_abonnements_user ON push_abonnements USING btree (user_id);

-- ---------------------------------------------------------------
-- user_artistes (#B : PK composite au lieu de UNIQUE seul)
-- ---------------------------------------------------------------

CREATE TABLE user_artistes (
    user_id           text                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    artiste_normalise text                     NOT NULL,
    artiste_affiche   text                     NOT NULL,
    created_at        timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (user_id, artiste_normalise)
);

CREATE INDEX idx_user_artistes_norm ON user_artistes USING btree (artiste_normalise);

-- ---------------------------------------------------------------
-- user_config (#J : ENUM — sans rayon_km supprimé en 0002)
-- ---------------------------------------------------------------

CREATE TABLE user_config (
    user_id                  text             PRIMARY KEY NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    departements             text[]           DEFAULT '{}' NOT NULL,
    filtre_disponibles       boolean          DEFAULT true NOT NULL,
    frequence_notifications  frequence_notif  DEFAULT 'jamais' NOT NULL,
    notif_artistes           boolean          DEFAULT true NOT NULL,
    notif_salles             boolean          DEFAULT false NOT NULL,
    theme                    theme_interface  DEFAULT 'auto' NOT NULL,
    updated_at               timestamp with time zone DEFAULT now() NOT NULL
);

-- ---------------------------------------------------------------
-- user_matches (#A : index corrigé — #B : PK composite — #H : sans vu_le)
-- ---------------------------------------------------------------

CREATE TABLE user_matches (
    user_id    text                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    concert_id uuid                     NOT NULL REFERENCES concerts(id) ON DELETE CASCADE,
    notifie_le timestamp with time zone,
    masque_le  timestamp with time zone,
    PRIMARY KEY (user_id, concert_id)
);

CREATE INDEX idx_user_matches_user     ON user_matches USING btree (user_id);
-- #A : corrigé — était WHERE vu_le IS NULL dans le schéma original
CREATE INDEX idx_user_matches_non_vus  ON user_matches USING btree (user_id) WHERE notifie_le IS NULL;

-- ---------------------------------------------------------------
-- user_salles (#C : FK vers salles, avec created_at)
-- ---------------------------------------------------------------

CREATE TABLE user_salles (
    user_id     text                     NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    salle_id    text                     NOT NULL REFERENCES salles(id)   ON DELETE CASCADE,
    tm_venue_id text,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (user_id, salle_id)
);
