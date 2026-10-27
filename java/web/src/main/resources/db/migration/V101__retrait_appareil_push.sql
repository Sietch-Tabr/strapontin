-- Début de la série d'échecs d'un abonnement push et date du dernier envoi réussi : un appareil
-- n'est retiré pour un échec autre que 404/410 qu'après plusieurs jours, et jamais quand tous les
-- envois vers le même service échouent (panne de l'application, pas de l'appareil).
ALTER TABLE push_abonnements ADD COLUMN premier_echec_le timestamptz;
ALTER TABLE push_abonnements ADD COLUMN dernier_envoi_reussi_le timestamptz;

-- Série en cours : repart de son dernier échec connu, la révocation n'en est que retardée.
UPDATE push_abonnements SET premier_echec_le = dernier_echec_le WHERE echecs_consecutifs > 0;

-- Comptes dont le dernier appareil a été retiré avant ce changement : leurs choix push, cochés
-- sans appareil, sont décochés (l'avertissement part au prochain passage du digest).
UPDATE user_config uc
SET notif_artistes_push = false, notif_salles_push = false, notif_nouvelle_version = false,
    frequence_notifications = CASE WHEN notif_artistes OR notif_salles
                                   THEN frequence_notifications ELSE 'jamais' END
WHERE uc.dernier_appareil_retire_le IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM push_abonnements pa WHERE pa.user_id = uc.user_id)
  AND (uc.notif_artistes_push OR uc.notif_salles_push OR uc.notif_nouvelle_version);
