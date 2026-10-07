-- V83__notifications_par_defaut.sql
--
-- Un nouveau compte reçoit par défaut un email hebdomadaire des concerts de ses artistes suivis.
-- Les comptes existants gardent leur réglage : ceux qui n'ont jamais enregistré de préférences
-- (aucune ligne user_config, donc rien reçu jusqu'ici) reçoivent une ligne qui fige cet état
-- avant le changement des valeurs par défaut.
INSERT INTO user_config (user_id, frequence_notifications, notif_artistes, notif_salles,
                         notif_artistes_push, notif_salles_push)
SELECT u.id, 'jamais', false, false, false, false
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM user_config uc WHERE uc.user_id = u.id);

ALTER TABLE user_config
  ALTER COLUMN frequence_notifications SET DEFAULT 'hebdomadaire',
  ALTER COLUMN notif_artistes          SET DEFAULT true;
