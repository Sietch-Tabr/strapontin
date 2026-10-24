-- Séance reconnue réelle par un administrateur alors qu'une billetterie ne la renvoie plus
-- (retrait de la vente, complet) : le compteur d'absences ne la signale plus à supprimer.
ALTER TABLE evenements ADD COLUMN maintenu_le timestamptz;
