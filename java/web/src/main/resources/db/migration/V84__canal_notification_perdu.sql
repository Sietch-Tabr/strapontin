-- V84__canal_notification_perdu.sql
--
-- Retrait automatique d'un appareil push (service du navigateur qui le refuse ou ne le connaît
-- plus) et avertissement envoyé quand ce retrait laisse le compte sans aucun canal joignable :
-- l'utilisateur n'en était jamais informé. L'avertissement est effacé dès qu'un canal redevient
-- joignable, pour qu'une nouvelle perte soit de nouveau signalée.
ALTER TABLE user_config ADD COLUMN dernier_appareil_retire_le timestamptz;
ALTER TABLE user_config ADD COLUMN avertissement_canal_perdu_le timestamptz;
