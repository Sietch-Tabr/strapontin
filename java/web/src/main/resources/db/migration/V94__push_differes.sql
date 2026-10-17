-- Envois push retenus pendant la plage silencieuse (22 h à 7 h, heure de Paris), repris à 7 h. La
-- ligne est la trace durable : le concert est marqué notifié dès qu'elle existe, elle disparaît
-- quand l'envoi a eu lieu.
CREATE TABLE push_differes (
    user_id    text         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    concert_id uuid         NOT NULL REFERENCES evenements(id) ON DELETE CASCADE,
    source     text         NOT NULL,
    cree_le    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, concert_id)
);
CREATE INDEX idx_push_differes_concert_id ON push_differes (concert_id);
