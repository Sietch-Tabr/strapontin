-- Dernière vérification de la commune d'une salle par sa position (géocodage inverse) : le contrôle
-- nocturne reprend par les salles les plus anciennement vérifiées, quelques-unes par nuit.
ALTER TABLE salles ADD COLUMN commune_verifiee_le timestamptz;
