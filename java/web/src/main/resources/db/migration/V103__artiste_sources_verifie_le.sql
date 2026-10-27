-- Dernière vérification d'un identifiant externe contre le nom de l'artiste (Eventim) : le contrôle
-- nocturne reprend par les identifiants les plus anciennement vérifiés.
ALTER TABLE artiste_sources ADD COLUMN verifie_le timestamptz;
