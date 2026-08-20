-- V31__coherence_wikidata_signalee.sql
--
-- Rattrapage du jeu de migrations web V51 — schéma dual, spring.flyway.enabled=false en prod côté
-- worker, mais nécessaire pour les tests d'intégration Testcontainers du worker.
ALTER TABLE salles ADD COLUMN coherence_wikidata_signalee text;
