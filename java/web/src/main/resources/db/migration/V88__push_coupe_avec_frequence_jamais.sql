-- V88__push_coupe_avec_frequence_jamais.sql
--
-- Une fréquence « jamais » coupe aussi le push : aucune livraison ne traite ce cas et aucun
-- garde-fou ne le signale. Le serveur impose désormais la cohérence, la migration aligne les
-- comptes déjà dans cet état.
UPDATE user_config
SET notif_artistes_push = false,
    notif_salles_push = false
WHERE frequence_notifications = 'jamais'
  AND (notif_artistes_push OR notif_salles_push);
