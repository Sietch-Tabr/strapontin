-- V32__nom_tm_incoherent_artiste_sources.sql
--
-- Rattrapage du jeu de migrations web V54 — schéma dual, spring.flyway.enabled=false en prod côté
-- worker, mais nécessaire pour les tests d'intégration Testcontainers du worker.
ALTER TABLE artiste_sources
    ADD COLUMN nom_tm_incoherent text,
    ADD COLUMN nom_tm_ignore text;
