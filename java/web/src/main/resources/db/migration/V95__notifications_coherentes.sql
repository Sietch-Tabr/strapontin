-- Une fréquence « Jamais » ne sert aucun canal : le contenu coché (emails, push, nouvelle version)
-- n'avait aucun effet et prêtait à confusion. Le serveur refuse désormais cette combinaison ; les
-- comptes existants sont alignés.
UPDATE user_config
SET notif_artistes = false, notif_salles = false, notif_artistes_push = false,
    notif_salles_push = false, notif_nouvelle_version = false
WHERE frequence_notifications = 'jamais'
  AND (notif_artistes OR notif_salles OR notif_artistes_push OR notif_salles_push
       OR notif_nouvelle_version);

-- Appareil d'un abonnement push, déduit du navigateur au moment de l'abonnement : sans lui,
-- l'utilisateur ne distingue pas ses appareils pour en révoquer un.
ALTER TABLE push_abonnements ADD COLUMN appareil text;
