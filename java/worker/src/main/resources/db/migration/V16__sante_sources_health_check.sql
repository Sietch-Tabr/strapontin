-- V16__sante_sources_health_check.sql
-- Suivi de santé des identifiants externes (#706) : compteur d'échecs consécutifs par source
-- externe, seuil 3 (~6h à la cadence 11 fetch/jour) déclenche le statut "suspect".

ALTER TABLE salle_sources
    ADD COLUMN nb_zero_consecutifs INT NOT NULL DEFAULT 0,
    ADD COLUMN suspect_depuis      TIMESTAMPTZ;

ALTER TABLE artiste_sources
    ADD COLUMN nb_zero_consecutifs INT NOT NULL DEFAULT 0,
    ADD COLUMN suspect_depuis      TIMESTAMPTZ;

CREATE INDEX idx_salle_sources_suspect   ON salle_sources   (suspect_depuis) WHERE suspect_depuis IS NOT NULL;
CREATE INDEX idx_artiste_sources_suspect ON artiste_sources (suspect_depuis) WHERE suspect_depuis IS NOT NULL;
