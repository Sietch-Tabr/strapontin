-- V27__absences_consecutives_concerts.sql
-- Détection d'un concert disparu du catalogue source : un concert dont la source ne renvoie plus
-- aucune trace lors des cycles de fetch successifs voit son compteur incrémenté ; au-delà d'un
-- seuil, il est marqué comme non confirmé récemment sans être masqué.

ALTER TABLE concerts
    ADD COLUMN absences_consecutives INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN absence_detectee_le TIMESTAMPTZ;
