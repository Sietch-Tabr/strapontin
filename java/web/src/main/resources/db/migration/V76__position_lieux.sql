-- V76__position_lieux.sql
--
-- Code postal du lieu tel que publié par chaque source (Ticketmaster, Eventim), au côté des
-- coordonnées déjà relevées : signal d'identité d'un lieu indépendant de son nom. Reporté sur la
-- salle tant qu'elle n'en a pas.
ALTER TABLE salle_sources ADD COLUMN code_postal text;
ALTER TABLE salles ADD COLUMN code_postal text;
