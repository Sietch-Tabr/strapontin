-- V80__vivacite_artiste_sources.sql
--
-- Dernière confirmation, par le contrôle de nuit, qu'un identifiant d'artiste chez une source est
-- vivant. Un artiste suivi sans date annoncée renvoie zéro résultat à chaque récupération : il
-- redevenait suspect chaque jour (77 sur 121 en production) et était revérifié chaque nuit, alors
-- qu'un identifiant mort est rare. Une fois confirmé vivant, il n'est plus revérifié avant 7 jours.
ALTER TABLE artiste_sources ADD COLUMN vivacite_verifiee_le timestamptz;
