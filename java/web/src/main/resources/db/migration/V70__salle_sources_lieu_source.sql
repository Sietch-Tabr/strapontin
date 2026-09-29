-- V70__salle_sources_lieu_source.sql
--
-- Lieu tel que la source le nomme (Eventim n'expose aucun identifiant de salle stable) :
-- libellé exact et ville requis par ses filtres venue_names/city_names, coordonnées pour
-- confronter une source interne à la position de la salle qu'elle désigne.

ALTER TABLE salle_sources ADD COLUMN libelle_source text;
ALTER TABLE salle_sources ADD COLUMN ville_source text;
ALTER TABLE salle_sources ADD COLUMN latitude double precision;
ALTER TABLE salle_sources ADD COLUMN longitude double precision;

-- Nombre de concerts renvoyés au dernier fetch de la salle : une chute brutale (le lieu
-- a changé de billetterie) passait inaperçue tant qu'au moins un concert restait listé.
ALTER TABLE salle_sources ADD COLUMN dernier_nb_resultats integer;
