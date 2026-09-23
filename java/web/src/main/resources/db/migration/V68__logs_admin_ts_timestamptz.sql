-- V68__logs_admin_ts_timestamptz.sql
-- logs_admin.ts était la seule colonne temporelle du schéma en bigint (epoch millisecondes) au
-- lieu de timestamp with time zone comme partout ailleurs — perd les fonctions natives
-- PostgreSQL sur les colonnes temporelles (arithmétique d'intervalle, lisibilité directe en
-- psql), sans raison technique identifiée justifiant cette exception.
ALTER TABLE logs_admin ALTER COLUMN ts TYPE timestamptz USING to_timestamp(ts / 1000.0);
