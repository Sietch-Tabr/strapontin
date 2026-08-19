-- V29__index_trigram_referentiel.sql
--
-- Rattrapage du jeu de migrations web V50 — schéma dual, spring.flyway.enabled=false en prod côté
-- worker, mais nécessaire pour les tests d'intégration Testcontainers du worker.

CREATE INDEX idx_salles_nom_trgm ON salles USING gin (nom gin_trgm_ops);
CREATE INDEX idx_salles_ville_trgm ON salles USING gin (ville gin_trgm_ops);
CREATE INDEX idx_artistes_nom_trgm ON artistes USING gin (nom gin_trgm_ops);
