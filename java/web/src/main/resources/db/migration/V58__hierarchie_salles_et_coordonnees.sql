-- V58__hierarchie_salles_et_coordonnees.sql
--
-- Un complexe multi-salles (ex. La Seine Musicale : Grande Seine / Auditorium Devedjian) n'avait
-- jusqu'ici qu'un seul salle_id pour plusieurs lieux physiques distincts, faute de modéliser la
-- relation complexe/sous-salle. salle_parent_id rattache une sous-salle au complexe plutôt que de
-- continuer à mélanger leurs concerts sous une seule identité.
--
-- latitude/longitude persistent les coordonnées Wikidata (P625), déjà récupérées en SPARQL pour la
-- validation au moment de l'enrichissement mais jamais stockées jusqu'ici — utilisées comme
-- garde-fou géographique supplémentaire lors d'une fusion de salles par coïncidence
-- artiste+date+heure+dept.
ALTER TABLE salles ADD COLUMN salle_parent_id uuid REFERENCES salles (id);
ALTER TABLE salles ADD COLUMN latitude double precision;
ALTER TABLE salles ADD COLUMN longitude double precision;
