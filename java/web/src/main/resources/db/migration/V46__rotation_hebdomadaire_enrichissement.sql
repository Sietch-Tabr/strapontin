-- V46 : rotation hebdomadaire par jour ISO pour l'enrichissement Wikidata/OSM/Deezer (#994)
-- Remplace PLAFOND_NOCTURNE (500, fixe) par un passage garanti toutes les 7 nuits, indépendamment
-- du nombre de concerts — évite la famine des candidats en tête de tri (une entité en échec
-- systématique, cf. #991, restait éternellement prioritaire).
--
-- jour_batch en 1-7 (pas 0-6) pour correspondre exactement à EXTRACT(ISODOW FROM date) de
-- Postgres (1=lundi...7=dimanche) : jour_batch = 3 se lit directement "traité le mercredi".
--
-- Séquence partagée entre salles et artistes : un seul objet à faire tourner, distribue les deux
-- pipelines sur les 7 jours sans risque de divergence de sémantique entre les deux tables. Le
-- DEFAULT (plutôt qu'une assignation côté Java) couvre uniformément tous les points d'insertion
-- (worker : ReconciliateurSalles/ReconciliateurArtistes ; web : SalleService/ArtisteService à
-- l'ajout interactif) sans toucher leur code, et backfille au passage les lignes déjà existantes
-- (ALTER TABLE ADD COLUMN avec DEFAULT non constant réévalue l'expression pour chaque ligne
-- existante).
CREATE SEQUENCE enrichissement_jour_batch_seq;

ALTER TABLE salles
  ADD COLUMN jour_batch smallint NOT NULL
  DEFAULT (((nextval('enrichissement_jour_batch_seq') - 1) % 7) + 1);

ALTER TABLE artistes
  ADD COLUMN jour_batch smallint NOT NULL
  DEFAULT (((nextval('enrichissement_jour_batch_seq') - 1) % 7) + 1);
