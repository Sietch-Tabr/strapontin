-- V65__notif_nouvelle_version.sql
-- Préférence opt-in de notification push lors d'une nouvelle version de l'application (#1231) —
-- défaut false pour tous, nouveaux comptes et comptes existants (pas de backfill à true requis,
-- contrairement à onboarding_vu, l'absence de ligne user_config équivaut déjà à false).
ALTER TABLE user_config ADD COLUMN notif_nouvelle_version boolean DEFAULT false NOT NULL;
