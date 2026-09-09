-- V41__signalements_matching.sql
--
-- Vue unifiée des signalements de matching (#1193) : au moins 4 mécanismes de détection
-- coexistaient déjà sans surface commune (necessite_intervention_le, contamination_signalee,
-- doublon_signale, collisions wikidata_id journalisées uniquement en logs). Cette table centralise
-- les nouveaux signalements de ce jalon (collision wikidata_id artiste, réattribution TM,
-- ambiguïté Wikidata, mots génériques suggérés, invariants généralistes) — les mécanismes déjà
-- établis restent à leur emplacement actuel, agrégés en lecture seule côté admin.
--
-- L'index unique partiel (type, entite_id) WHERE resolu_le IS NULL empêche de re-signaler
-- indéfiniment une même incohérence déjà active, même patron que coherence_wikidata_signalee.
CREATE TABLE signalements_matching (
    id          bigserial PRIMARY KEY,
    type        text NOT NULL,
    entite_type text NOT NULL,
    entite_id   uuid NOT NULL,
    confiance   text NOT NULL,
    detecte_le  timestamptz NOT NULL DEFAULT now(),
    resolu_le   timestamptz,
    details     jsonb
);
CREATE UNIQUE INDEX idx_signalements_actifs ON signalements_matching (type, entite_id)
    WHERE resolu_le IS NULL;
