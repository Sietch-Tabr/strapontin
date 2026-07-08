-- V44__necessite_intervention_artiste_sources.sql
-- #888 : le compteur passif (nb_zero_consecutifs/suspect_depuis) génère trop de faux positifs
-- côté artistes TM pour être exposé tel quel. Le signal fiable est la vérification active
-- nocturne (ReconciliationNocturneService) : cette colonne n'est posée que lorsque la
-- redécouverte a réellement échoué (ID confirmé mort ET aucun remplaçant trouvé).

ALTER TABLE artiste_sources
    ADD COLUMN necessite_intervention_le TIMESTAMPTZ;

CREATE INDEX idx_artiste_sources_necessite_intervention
    ON artiste_sources (necessite_intervention_le) WHERE necessite_intervention_le IS NOT NULL;
