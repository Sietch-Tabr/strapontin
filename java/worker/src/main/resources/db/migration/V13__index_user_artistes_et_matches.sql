-- V13__index_user_artistes_et_matches.sql
-- #736 : Équivalent worker de V36 web (tests d'intégration Testcontainers).
-- Recréer idx_user_artistes_artiste_id, ajouter idx_user_matches_concert.

CREATE INDEX idx_user_artistes_artiste_id ON user_artistes (artiste_id);
CREATE INDEX idx_user_matches_concert ON user_matches (concert_id);
