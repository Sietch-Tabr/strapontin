-- Notification de nouvelle version : une seule par version et par utilisateur, qu'elle parte du
-- cycle automatique de 11 h ou du bouton de l'admin.
CREATE TABLE notifications_version (
    user_id    text         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version    text         NOT NULL,
    envoyee_le timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, version)
);
