-- V67__index_user_salles_salle_id.sql
-- Même problème déjà corrigé pour user_artistes en V36 : la clé primaire composite
-- (user_id, salle_id) ne sert pas efficacement un filtre sur salle_id seul — l'index avait été
-- créé en V23 (idx_user_salles_salle_uuid) puis supprimé en V24, jamais recréé après.
CREATE INDEX idx_user_salles_salle_id ON user_salles (salle_id);
