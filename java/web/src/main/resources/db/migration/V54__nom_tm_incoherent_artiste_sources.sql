-- V54__nom_tm_incoherent_artiste_sources.sql
--
-- ReconciliationNocturneService (healthCheckNomsArtistesTM) posait necessite_intervention_le sur
-- tout écart de nom TM, y compris purement cosmétique (espace, article) désormais absorbé par une
-- comparaison floue. Pour l'écart non cosmétique restant (nom de scène, ex. -M-/Matthieu Chédid),
-- ces colonnes mémorisent le nom TM à l'origine de la divergence courante et le dernier nom TM
-- explicitement accepté par un admin (ignorerIntervention) — évite de reposer indéfiniment le même
-- flag pour une divergence déjà jugée acceptable, tout en re-signalant si le nom TM change encore.
ALTER TABLE artiste_sources
    ADD COLUMN nom_tm_incoherent text,
    ADD COLUMN nom_tm_ignore text;
