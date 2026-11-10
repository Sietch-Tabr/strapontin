-- V108__invitations_utilisateurs.sql
--
-- Invitations envoyées par les utilisateurs. Aucun compte n'existe avant l'activation du lien :
-- l'invitation vit dans sa table, le compte créé garde la trace de son parrain. Les envois
-- (premier envoi et renvois) sont journalisés à part pour que l'annulation d'une invitation ne
-- remette pas à zéro la cadence.
ALTER TABLE users ADD COLUMN parrain_id text REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN plafond_invitations integer
    CONSTRAINT ck_users_plafond_invitations CHECK (plafond_invitations >= 0);
CREATE INDEX idx_users_parrain_id ON users (parrain_id);

CREATE TABLE invitations_utilisateurs (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parrain_id text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      text NOT NULL,
    token_hash text NOT NULL UNIQUE,
    cree_le    timestamptz NOT NULL DEFAULT now(),
    expire_le  timestamptz NOT NULL,
    active_le  timestamptz,
    annulee_le timestamptz
);
CREATE INDEX idx_invitations_utilisateurs_parrain ON invitations_utilisateurs (parrain_id, cree_le DESC);
CREATE INDEX idx_invitations_utilisateurs_email ON invitations_utilisateurs (lower(email));

CREATE TABLE envois_invitations (
    id         bigserial PRIMARY KEY,
    parrain_id text NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    envoye_le  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_envois_invitations_parrain ON envois_invitations (parrain_id, envoye_le);
CREATE INDEX idx_envois_invitations_envoye_le ON envois_invitations (envoye_le);
