-- V49__index_user_salles_salle_id.sql
-- Réplique V67 côté web — même problème déjà corrigé pour user_artistes en V13
-- (idx_user_artistes_artiste_id), jamais répliqué côté user_salles.
CREATE INDEX idx_user_salles_salle_id ON user_salles (salle_id);
