-- Un retry ciblé ne traite que les lots en échec d'un cycle : son volume partiel n'est pas
-- comparable à celui d'un cycle complet et ne doit pas entrer dans la moyenne de référence de la
-- dérive de volume.
ALTER TABLE fetch_jobs_historique ADD COLUMN retry_cible boolean NOT NULL DEFAULT false;
