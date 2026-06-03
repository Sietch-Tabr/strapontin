ALTER TABLE user_config
  ALTER COLUMN notif_artistes      SET DEFAULT false,
  ALTER COLUMN notif_artistes_push SET DEFAULT false,
  ALTER COLUMN notif_salles_push   SET DEFAULT false;
