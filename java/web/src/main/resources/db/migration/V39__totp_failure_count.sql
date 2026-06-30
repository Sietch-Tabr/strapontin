-- V39__totp_failure_count.sql
-- Compteur d'échecs TOTP persisté en base de données.
-- Remplace le compteur en session HTTP (SESSION_ECHECS_TOTP) perdu au logout/restart.
-- Dépasse MAX_ECHECS_TOTP (5) → verrouillage persistant, reset par admin via /api/admin/utilisateurs/{id}/reset-totp.

ALTER TABLE users ADD COLUMN totp_failure_count INT NOT NULL DEFAULT 0;
