-- V64__purge_comptes_inactifs.sql
-- Purge des comptes inactifs depuis 6 mois (relance + délai de grâce 15 jours) et des comptes
-- invités jamais activés (relance J+6, suppression J+7 si le token d'invitation expire sans
-- activation). derniere_connexion_le est mis à jour à chaque connexion réussie et purge_le est
-- remis à NULL au même moment (reconnexion = annulation automatique de la purge en cours).

ALTER TABLE users ADD COLUMN derniere_connexion_le TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN purge_le TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN relance_invitation_envoyee_le TIMESTAMP WITH TIME ZONE;
