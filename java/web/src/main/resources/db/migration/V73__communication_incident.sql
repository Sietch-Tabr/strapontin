-- V73__communication_incident.sql
--
-- Communication d'un incident de sécurité aux utilisateurs : une seule communication active à la
-- fois, affichée à tous (y compris non connectés) jusqu'à sa clôture par un admin.
CREATE TABLE communications_incident (
    id                     bigserial   PRIMARY KEY,
    titre                  text        NOT NULL,
    message                text        NOT NULL,
    publiee_le             timestamptz NOT NULL DEFAULT now(),
    terminee_le            timestamptz,
    nb_emails              integer     NOT NULL DEFAULT 0,
    nb_push                integer     NOT NULL DEFAULT 0,
    changement_mdp_force   boolean     NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uq_communication_incident_active
    ON communications_incident ((true)) WHERE terminee_le IS NULL;

-- Changement de mot de passe exigé à la prochaine connexion (levé au changement effectif).
ALTER TABLE users ADD COLUMN changement_mdp_force_le timestamptz;
