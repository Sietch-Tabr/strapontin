-- Fusion du doublon artiste Krash Karma / Krashkarma (#660).
-- Krash Karma (nom_norm='krash karma') est l'artiste canonique (entrée artiste_sources active).
-- Krashkarma (nom_norm='krashkarma') est le doublon à absorber.
-- Idempotent : ne fait rien si l'un des deux est introuvable ou s'ils sont identiques.

CREATE OR REPLACE FUNCTION v28_fusionner_doublon_artiste(
    p_nom_norm_canonique text,
    p_nom_norm_doublon text
) RETURNS void AS $$
DECLARE
    v_id_canonique UUID;
    v_id_doublon UUID;
BEGIN
    SELECT id INTO v_id_canonique FROM artistes WHERE nom_norm = p_nom_norm_canonique LIMIT 1;
    SELECT id INTO v_id_doublon   FROM artistes WHERE nom_norm = p_nom_norm_doublon   LIMIT 1;

    IF v_id_canonique IS NULL OR v_id_doublon IS NULL OR v_id_canonique = v_id_doublon THEN
        RETURN;
    END IF;

    UPDATE concerts SET artiste_id = v_id_canonique WHERE artiste_id = v_id_doublon;

    -- Supprimer les entrées dont l'utilisateur suit déjà l'artiste canonique
    -- (sinon UPDATE violerait la PK (user_id, artiste_id))
    DELETE FROM user_artistes
    WHERE artiste_id = v_id_doublon
      AND EXISTS (
        SELECT 1 FROM user_artistes ua2
        WHERE ua2.user_id = user_artistes.user_id
          AND ua2.artiste_id = v_id_canonique
      );
    UPDATE user_artistes SET artiste_id = v_id_canonique WHERE artiste_id = v_id_doublon;

    UPDATE artiste_sources SET artiste_id = v_id_canonique WHERE artiste_id = v_id_doublon;
    DELETE FROM artistes WHERE id = v_id_doublon;
END;
$$ LANGUAGE plpgsql;

-- Krash Karma (canonique) ← Krashkarma (doublon, même attractionId TM K8vZ917GD77)
SELECT v28_fusionner_doublon_artiste('krash karma', 'krashkarma');

DROP FUNCTION v28_fusionner_doublon_artiste(text, text);
