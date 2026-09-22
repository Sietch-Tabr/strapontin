-- V52__push_abonnements_echecs_403.sql
--
-- #1219 : un abonnement push en échec HTTP 403 permanent (VAPID/subscription invalide côté FCM,
-- distinct d'un 404/410 déjà nettoyé) n'était jamais détecté ni nettoyé — compteur passif, remis à
-- zéro sur un envoi réussi, même pattern que artiste_sources.nb_zero_consecutifs.

ALTER TABLE push_abonnements ADD COLUMN echecs_403_consecutifs smallint DEFAULT 0 NOT NULL;
