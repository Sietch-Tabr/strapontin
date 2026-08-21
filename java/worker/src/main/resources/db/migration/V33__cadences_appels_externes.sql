-- V33__cadences_appels_externes.sql
--
-- Rattrapage du jeu de migrations web V55 — schéma dual, spring.flyway.enabled=false en prod côté
-- worker, mais nécessaire pour les tests d'intégration Testcontainers du worker.
CREATE TABLE cadences_appels_externes (
    cle                     text PRIMARY KEY,
    prochain_appel_autorise timestamptz NOT NULL
);

INSERT INTO cadences_appels_externes (cle, prochain_appel_autorise)
VALUES ('ticketmaster', now());
