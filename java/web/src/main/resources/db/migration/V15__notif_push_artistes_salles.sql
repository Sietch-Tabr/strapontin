ALTER TABLE user_config
  ADD COLUMN notif_artistes_push boolean NOT NULL DEFAULT true,
  ADD COLUMN notif_salles_push   boolean NOT NULL DEFAULT true;
