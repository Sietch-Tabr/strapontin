-- V46__notif_nouvelle_version.sql
-- Réplique V65 côté web (#1231) — préférence opt-in de notification push lors d'une nouvelle
-- version de l'application, lue par le worker pour cibler les abonnements à notifier.
ALTER TABLE user_config ADD COLUMN notif_nouvelle_version boolean DEFAULT false NOT NULL;
