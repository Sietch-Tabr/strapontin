-- V82__frequence_hebdomadaire.sql
--
-- Récapitulatif hebdomadaire (dimanche 10h). Une valeur d'enum ajoutée n'est utilisable qu'après
-- validation de la transaction qui la crée : les défauts qui l'emploient sont posés par V83.
ALTER TYPE frequence_notif ADD VALUE IF NOT EXISTS 'hebdomadaire';
