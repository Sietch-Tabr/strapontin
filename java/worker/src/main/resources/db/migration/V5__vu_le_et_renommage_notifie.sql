ALTER TABLE user_matches
  RENAME COLUMN notifie_le TO notification_envoyee_le;

ALTER TABLE user_matches
  ADD COLUMN vu_le timestamptz;

UPDATE user_matches
SET vu_le = notification_envoyee_le
WHERE notification_envoyee_le IS NOT NULL;
