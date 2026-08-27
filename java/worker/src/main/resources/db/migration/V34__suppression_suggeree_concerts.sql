-- V34__suppression_suggeree_concerts.sql
-- Purge des concerts jamais reconfirmés : remplace la suppression automatique par un flag de
-- suggestion, revu manuellement depuis l'admin avant toute suppression définitive.

ALTER TABLE concerts
    ADD COLUMN suppression_suggeree_le TIMESTAMPTZ;
