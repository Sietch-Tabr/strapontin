-- V5__supprimer_index_redondants.sql
-- Supprime les index redondants avec les clés primaires ou contraintes UNIQUE existantes.
--
-- idx_user_matches_user(user_id) : redondant avec PK (user_id, concert_id)
-- idx_push_abonnements_user(user_id) : redondant avec UNIQUE (user_id, endpoint)

DROP INDEX IF EXISTS idx_user_matches_user;
DROP INDEX IF EXISTS idx_push_abonnements_user;
