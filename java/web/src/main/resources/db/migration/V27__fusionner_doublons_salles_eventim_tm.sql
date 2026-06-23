-- Fusion des 7 doublons de salles Eventim/TM (#659).
-- Les salles Eventim sont identifiées par salle_cle (normaliserTexte du nom Eventim brut).
-- Les salles TM sont identifiées par leur identifiant_externe dans salle_sources.
-- Idempotent : ne fait rien si l'un des deux UUID est introuvable ou s'ils sont identiques.

CREATE OR REPLACE FUNCTION v27_fusionner_doublon_salle(
    p_identifiant_tm text,
    p_salle_cle_eventim text
) RETURNS void AS $$
DECLARE
    v_uuid_tm UUID;
    v_uuid_eventim UUID;
BEGIN
    SELECT ss.salle_id INTO v_uuid_tm
    FROM salle_sources ss
    WHERE ss.source = 'ticketmaster' AND ss.identifiant_externe = p_identifiant_tm;

    SELECT s.id INTO v_uuid_eventim
    FROM salles s
    WHERE s.salle_cle = p_salle_cle_eventim
      AND NOT EXISTS (
          SELECT 1 FROM salle_sources ss2
          WHERE ss2.salle_id = s.id AND ss2.source = 'ticketmaster'
      );

    IF v_uuid_tm IS NULL OR v_uuid_eventim IS NULL OR v_uuid_tm = v_uuid_eventim THEN
        RETURN;
    END IF;

    UPDATE concerts SET salle_uuid = v_uuid_tm WHERE salle_uuid = v_uuid_eventim;
    UPDATE user_salles SET salle_uuid = v_uuid_tm WHERE salle_uuid = v_uuid_eventim;
    DELETE FROM salle_sources WHERE salle_id = v_uuid_eventim;
    DELETE FROM salles WHERE id = v_uuid_eventim;
END;
$$ LANGUAGE plpgsql;

-- 1. Zénith de Nantes Métropole (Eventim) ↔ Zenith Nantes Metropole (TM rZFSnyZaF1A, sim=0.885)
SELECT v27_fusionner_doublon_salle('rZFSnyZaF1A', 'zenith de nantes metropole');

-- 2. Carré des Docks Le Havre Normandie (Eventim) ↔ TM rZ7SnyZa7TA (sim=0.791)
SELECT v27_fusionner_doublon_salle('rZ7SnyZa7TA', 'carre des docks le havre normandie le havre');

-- 3. Zénith dAuvergne - Clermont-Ferrand (Eventim) ↔ Zenith D'Auvergne (TM rZFSnyZAa1a, sim=0.455)
SELECT v27_fusionner_doublon_salle('rZFSnyZAa1a', 'zenith dauvergne clermont ferrand');

-- 4. THEATRE DE CHAMPAGNE TROYES (Eventim) ↔ Theatre de Champagne Troyes (TM rZ7SnyZad0w, sim=0.741)
SELECT v27_fusionner_doublon_salle('rZ7SnyZad0w', 'theatre de champagne troyes');

-- 5. LA COMMANDERIE DOLE (Eventim) ↔ La Commanderie Dole (TM rZFSnyZ1dkv, sim=0.737)
SELECT v27_fusionner_doublon_salle('rZFSnyZ1dkv', 'la commanderie dole');

-- 6. BREST ARENA BREST (Eventim) ↔ Brest Arena (TM rZ7SnyZa7-a, sim=0.619)
SELECT v27_fusionner_doublon_salle('rZ7SnyZa7-a', 'brest arena brest');

-- 7. Chateau de Chambord (Eventim) ↔ CHÂTEAU DE CHAMBORD (TM rZ7SnyZadwm, sim=0.679)
SELECT v27_fusionner_doublon_salle('rZ7SnyZadwm', 'chateau de chambord');

DROP FUNCTION v27_fusionner_doublon_salle(text, text);
