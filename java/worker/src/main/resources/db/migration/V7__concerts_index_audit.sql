-- V7__concerts_index_audit.sql
-- Ajustements d'index sur concerts d'après l'audit de production (ticket #587).
--
-- Réplique de la migration web V20. idx_concerts_source : idx_scan = 0 mesuré en prod (supprimé).
-- Nouvel index pour le self-join de suppression des doublons par URL (SQL_DOUBLONS_URL, #586).

DROP INDEX IF EXISTS idx_concerts_source;

CREATE INDEX idx_concerts_url ON concerts USING btree (source, url, date)
    WHERE url IS NOT NULL;
