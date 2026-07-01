-- #739 : ajouter 'digitick' à l'ENUM source_concert (alignement avec web V34).
ALTER TYPE source_concert ADD VALUE IF NOT EXISTS 'digitick';
