-- V36__index_user_artistes_et_matches.sql
-- #736 : Recréer idx_user_artistes_artiste_id (supprimé en V24, jamais recréé).
-- Ajouter idx_user_matches_concert pour les DELETE CASCADE de PurgerConcertsJob.
--
-- Impact prod : 69 000+ sequential scans sur user_artistes, seq scans sur user_matches
-- lors des purges quotidiennes de concerts passés.

CREATE INDEX idx_user_artistes_artiste_id ON user_artistes (artiste_id);
CREATE INDEX idx_user_matches_concert ON user_matches (concert_id);
