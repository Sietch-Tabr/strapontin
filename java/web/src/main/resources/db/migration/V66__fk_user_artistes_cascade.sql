-- V66__fk_user_artistes_cascade.sql
-- user_artistes_artiste_id_fkey était en ON DELETE SET NULL alors qu'artiste_id est NOT NULL et
-- fait partie de la clé primaire composite (user_id, artiste_id) — toute suppression d'un artiste
-- encore suivi violerait immédiatement cette contrainte. user_salles_salle_uuid_fkey (nom hérité
-- du renommage salle_uuid -> salle_id, PostgreSQL ne renomme pas la contrainte) gère déjà
-- correctement ce même cas via ON DELETE CASCADE.
ALTER TABLE user_artistes DROP CONSTRAINT user_artistes_artiste_id_fkey;
ALTER TABLE user_artistes
    ADD CONSTRAINT user_artistes_artiste_id_fkey
    FOREIGN KEY (artiste_id) REFERENCES artistes(id) ON DELETE CASCADE;
