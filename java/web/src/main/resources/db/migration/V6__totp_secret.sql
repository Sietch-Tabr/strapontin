-- V6__totp_secret.sql
-- Ajoute la colonne stockant le secret TOTP (Base32) pour la 2FA admin.
-- NULL = TOTP non configuré sur ce compte.

ALTER TABLE users ADD COLUMN two_factor_secret TEXT;
