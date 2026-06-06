CREATE INDEX IF NOT EXISTS idx_user_matches_non_masques ON user_matches (user_id) WHERE masque_le IS NULL;
