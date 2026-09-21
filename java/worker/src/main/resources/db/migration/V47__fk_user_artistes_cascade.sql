-- V47__fk_user_artistes_cascade.sql
-- Réplique V66 côté web — user_artistes_artiste_id_fkey était en ON DELETE SET NULL alors
-- qu'artiste_id est NOT NULL et fait partie de la clé primaire composite (user_id, artiste_id).
ALTER TABLE user_artistes DROP CONSTRAINT user_artistes_artiste_id_fkey;
ALTER TABLE user_artistes
    ADD CONSTRAINT user_artistes_artiste_id_fkey
    FOREIGN KEY (artiste_id) REFERENCES artistes(id) ON DELETE CASCADE;
