-- V50__notif_artistes_default_et_index_redondants.sql
-- Réplique côté worker deux migrations web jamais reproduites :
-- V16 (DEFAULT notif_artistes false, lu directement par MatchesNonNotifiesLoader) — le worker
-- était resté au défaut de V1 (true), risque de divergence silencieuse test/prod.
-- V5 (retrait des 2 index redondants avec des contraintes déjà existantes).

ALTER TABLE user_config ALTER COLUMN notif_artistes SET DEFAULT false;

DROP INDEX IF EXISTS idx_user_matches_user;
DROP INDEX IF EXISTS idx_push_abonnements_user;
