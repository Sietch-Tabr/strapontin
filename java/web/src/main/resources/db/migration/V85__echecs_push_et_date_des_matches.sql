-- V85__echecs_push_et_date_des_matches.sql
--
-- Échecs push : tout échec répété (et plus seulement un 403) mène à la révocation de l'appareil,
-- le compteur est donc renommé. La cause du dernier retrait est conservée sur le compte pour que
-- l'avertissement de l'utilisateur l'explique.
ALTER TABLE push_abonnements RENAME COLUMN echecs_403_consecutifs TO echecs_consecutifs;
ALTER TABLE user_config ADD COLUMN cause_dernier_retrait_appareil text;

-- Date de rapprochement d'un concert avec un compte : le délai de notification attendu part de
-- là, et non de la première détection du concert, qui peut précéder de loin le suivi de l'artiste
-- ou de la salle. Les matches existants reçoivent la date de la migration : leur délai repart.
ALTER TABLE user_matches ADD COLUMN cree_le timestamptz NOT NULL DEFAULT now();
