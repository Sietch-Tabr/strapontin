-- V52__onboarding_vu.sql
--
-- Modale d'onboarding affichée une seule fois aux nouveaux utilisateurs invités. Backfill à true
-- pour ne pas l'imposer rétroactivement aux comptes existants.
ALTER TABLE user_config ADD COLUMN onboarding_vu boolean DEFAULT false NOT NULL;
UPDATE user_config SET onboarding_vu = true;
