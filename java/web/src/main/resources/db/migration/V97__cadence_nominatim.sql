-- V97__cadence_nominatim.sql
--
-- Nominatim limite à 1 requête par seconde pour l'ensemble de l'adresse sortante, que partagent le
-- worker (géocodage des salles) et le web (recherche interactive à l'ajout) : une ligne dans le
-- cadenceur partagé, comme Ticketmaster, pour que les deux processus se répartissent la seconde.
INSERT INTO cadences_appels_externes (cle, prochain_appel_autorise)
VALUES ('nominatim', now());
