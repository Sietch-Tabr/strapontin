-- Rattrapage du jeu de migrations web (V17) : NotificationDigestService et
-- RecalculerMatchesService filtrent tous deux sur user_matches.masque_le IS NULL.
CREATE INDEX IF NOT EXISTS idx_user_matches_non_masques ON user_matches (user_id) WHERE masque_le IS NULL;
