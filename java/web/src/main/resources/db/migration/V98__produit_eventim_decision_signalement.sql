-- Un même produit Eventim garde son identifiant numérique quand son titre ou son heure change :
-- l'index sert à retrouver l'événement qui le porte déjà avant d'en créer un autre.
CREATE INDEX idx_observations_produit_eventim
    ON observations ((substring(url FROM '-([0-9]+)/?$')))
    WHERE source = 'eventim';

-- Distingue la résolution par un administrateur (décision à conserver) de la clôture
-- automatique d'un contrôle périodique dont l'anomalie a disparu.
ALTER TABLE signalements_matching
    ADD COLUMN resolu_manuellement boolean NOT NULL DEFAULT false;
