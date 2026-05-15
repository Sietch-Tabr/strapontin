-- V4__logs_admin.sql
-- Logs admin (remplace la liste Redis strapontin:admin:logs) + stats des jobs Quartz

CREATE TABLE logs_admin (
    id       bigserial                PRIMARY KEY,
    ts       bigint                   NOT NULL,
    niveau   text                     NOT NULL,
    source   text                     NOT NULL,
    message  text                     NOT NULL,
    meta     jsonb
);

CREATE INDEX idx_logs_admin_ts ON logs_admin (ts DESC);

CREATE TABLE fetch_jobs (
    type_job       text                     PRIMARY KEY NOT NULL,
    derniere_exec  timestamp with time zone,
    duree_ms       integer,
    nb_inseres     integer,
    nb_mis_a_jour  integer,
    erreur         text
);
