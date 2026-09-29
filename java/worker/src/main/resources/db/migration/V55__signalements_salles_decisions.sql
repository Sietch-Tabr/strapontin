-- V55__signalements_salles_decisions.sql
--
-- doublon_signale devient une vraie référence : une fusion ou une purge de salle laissait sinon
-- le signalement pointer vers une salle disparue. Les valeurs déjà orphelines sont effacées.
UPDATE salles SET doublon_signale = NULL
WHERE doublon_signale IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM salles cible WHERE cible.id::text = salles.doublon_signale);
ALTER TABLE salles ALTER COLUMN doublon_signale TYPE uuid USING doublon_signale::uuid;
ALTER TABLE salles ADD CONSTRAINT fk_salles_doublon_signale
    FOREIGN KEY (doublon_signale) REFERENCES salles(id) ON DELETE SET NULL;

-- Décisions admin mémorisées, pour ne jamais re-signaler chaque nuit ce qu'un humain a vérifié :
-- une source interne jugée légitime malgré un nom sans rapport (nom d'usage, sigle officiel)...
ALTER TABLE salle_sources ADD COLUMN contamination_acceptee_le timestamptz;

-- ...et deux salles jugées distinctes malgré des noms qui se contiennent.
CREATE TABLE salles_paires_distinctes (
    salle_a     uuid NOT NULL REFERENCES salles(id) ON DELETE CASCADE,
    salle_b     uuid NOT NULL REFERENCES salles(id) ON DELETE CASCADE,
    decidee_le  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (salle_a, salle_b),
    CHECK (salle_a < salle_b)
);
