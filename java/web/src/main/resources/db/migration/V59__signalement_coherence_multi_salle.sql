-- V59__signalement_coherence_multi_salle.sql
--
-- Débounce des deux nouveaux contrôles nocturnes de CoherenceMultiSalleService, même patron que
-- coherence_wikidata_signalee (V51) : mémorise la dernière signature déjà journalisée pour ne pas
-- re-signaler indéfiniment un état déjà connu.
--
-- contamination_signalee : un salle_id dont au moins une source interne (Eventim/Digitick) porte
-- un nom qui ne correspond plus au nom de la salle — signe qu'il héberge probablement plusieurs
-- lieux réels distincts (#1177).
--
-- doublon_signale : id de l'autre salle d'une paire déjà signalée comme doublon potentiel sans
-- wikidata_id ni concert partagé (#1182) — mémorisé sur la salle au plus petit id de la paire.
ALTER TABLE salles ADD COLUMN contamination_signalee text;
ALTER TABLE salles ADD COLUMN doublon_signale text;
