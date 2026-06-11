-- V11__concerts_index_audit.sql
-- Ajustements d'index sur concerts d'après l'audit pg_stat_user_indexes en production (ticket #587).
--
-- idx_concerts_source : idx_scan = 0 mesuré en prod (aucune requête ne filtre WHERE source = ...).
--   La déduplication utilise source dans un self-join, pas un filtre sélectif. Index supprimé.
-- idx_concerts_disponible : conservé (idx_scan = 498 en prod, contrairement à l'hypothèse initiale).
--
-- Nouvel index pour le self-join de suppression des doublons par URL (SQL_DOUBLONS_URL, #586) :
--   c1.source = c2.source AND c1.url = c2.url AND c1.date = c2.date, filtré sur url IS NOT NULL.

DROP INDEX IF EXISTS idx_concerts_source;

CREATE INDEX idx_concerts_url ON concerts USING btree (source, url, date)
    WHERE url IS NOT NULL;
