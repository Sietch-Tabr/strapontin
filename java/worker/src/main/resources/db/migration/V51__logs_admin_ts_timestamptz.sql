-- V51__logs_admin_ts_timestamptz.sql
-- Réplique V68 côté web.
ALTER TABLE logs_admin ALTER COLUMN ts TYPE timestamptz USING to_timestamp(ts / 1000.0);
