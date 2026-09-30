-- V74__index_cles_etrangeres.sql
--
-- Clés étrangères sans index dont elles forment le préfixe : chaque suppression dans la table
-- référencée (compte, fusion de salles) imposait un parcours séquentiel de la table référençante.
CREATE INDEX idx_accounts_user_id ON accounts (user_id);
CREATE INDEX idx_salles_doublon_signale ON salles (doublon_signale) WHERE doublon_signale IS NOT NULL;
CREATE INDEX idx_salles_salle_parent_id ON salles (salle_parent_id) WHERE salle_parent_id IS NOT NULL;
CREATE INDEX idx_salles_paires_distinctes_salle_b ON salles_paires_distinctes (salle_b);
