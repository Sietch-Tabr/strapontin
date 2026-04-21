-- Migration 0001 : remplace notifications_email (boolean) par frequence_notifications (text)
-- Migre les users qui avaient notifications_email = true vers 'temps_reel'

ALTER TABLE "user_config" ADD COLUMN "frequence_notifications" text NOT NULL DEFAULT 'jamais';
UPDATE "user_config" SET "frequence_notifications" = 'temps_reel' WHERE "notifications_email" = true;
ALTER TABLE "user_config" DROP COLUMN "notifications_email";
ALTER TABLE "user_config" ADD CONSTRAINT "chk_frequence_notifications" CHECK ("frequence_notifications" IN ('jamais', 'temps_reel', 'quotidien'));
