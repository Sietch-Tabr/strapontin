-- V87__evenement_artistes.sql
--
-- Autres artistes d'une soirée partagée (festival, co-plateau) : evenements.artiste_id n'en
-- porte qu'un, et les suiveurs des autres n'étaient jamais notifiés. Le nom publié est gardé
-- même quand l'artiste n'existe pas encore au référentiel ; artiste_id est relié dès qu'il y
-- entre (suivi par un utilisateur).
CREATE TABLE evenement_artistes (
    evenement_id uuid NOT NULL REFERENCES evenements(id) ON DELETE CASCADE,
    artiste_norm text NOT NULL,
    artiste_nom  text NOT NULL,
    artiste_id   uuid REFERENCES artistes(id) ON DELETE SET NULL,
    PRIMARY KEY (evenement_id, artiste_norm)
);

CREATE INDEX idx_evenement_artistes_artiste_id ON evenement_artistes (artiste_id);
CREATE INDEX idx_evenement_artistes_artiste_norm ON evenement_artistes (artiste_norm);
