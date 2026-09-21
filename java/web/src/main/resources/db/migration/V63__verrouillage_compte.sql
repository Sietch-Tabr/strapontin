-- V63__verrouillage_compte.sql
-- Verrouillage immédiat d'un compte suspecté compromis par un admin (bloque
-- la connexion, indépendant de la révocation admin qui ne fait que rétrograder le rôle).

ALTER TABLE users ADD COLUMN verrouille BOOLEAN NOT NULL DEFAULT false;
