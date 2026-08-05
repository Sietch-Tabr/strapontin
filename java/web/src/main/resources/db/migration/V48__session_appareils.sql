-- V48__session_appareils.sql
-- Capture du user-agent à la connexion pour permettre à un utilisateur de retrouver et révoquer
-- individuellement ses sessions actives depuis /parametres. SPRING_SESSION (schéma tiers, V3) ne
-- porte aucune colonne exploitable pour identifier un appareil — cette table lui est associée
-- séparément plutôt que d'altérer son schéma.

-- appareil_id (et non session_id) est l'identifiant exposé côté client : le résultat d'un
-- éventuel XSS ne doit jamais permettre de lire le véritable identifiant de session (utilisable
-- tel quel pour usurper la session), contrairement à un identifiant opaque sans rapport.
CREATE TABLE session_appareils (
    appareil_id uuid                     PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  text                     NOT NULL UNIQUE REFERENCES SPRING_SESSION(SESSION_ID) ON DELETE CASCADE,
    user_id     text                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_agent  text,
    cree_le     timestamptz              DEFAULT now() NOT NULL
);

CREATE INDEX idx_session_appareils_user ON session_appareils (user_id);
