-- V23 : rattrapage du jeu de migrations worker, jamais synchronisé avec web V15/V16
-- (NotificationDigestService interroge uc.notif_artistes_push/notif_salles_push, colonnes
-- absentes du schéma worker — échec SQL silencieux, capté et journalisé en warn, sur toute
-- exécution de fetch:*/notifier:digest dans les tests d'intégration Testcontainers du worker ;
-- sans impact prod, où seul web migre — trouvé en session 2.7.3 en testant #996).
ALTER TABLE user_config
  ADD COLUMN notif_artistes_push boolean NOT NULL DEFAULT false,
  ADD COLUMN notif_salles_push   boolean NOT NULL DEFAULT false;
