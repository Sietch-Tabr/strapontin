-- Mode d'affichage annoncé par le client à l'ouverture de la session (pwa ou navigateur) : le
-- User-Agent est identique dans les deux cas. NULL pour les sessions antérieures, comparées sans
-- tenir compte du mode.
ALTER TABLE session_appareils ADD COLUMN mode_affichage text;
