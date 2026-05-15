-- V2__verifications.sql
-- Table de vérification pour les tokens de reset MDP (Better Auth)

CREATE TABLE verifications (
    id         text                     PRIMARY KEY NOT NULL,
    identifier text                     NOT NULL,
    value      text                     NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now()
);

CREATE INDEX idx_verifications_identifier ON verifications (identifier);
CREATE INDEX idx_verifications_expires_at ON verifications (expires_at);
