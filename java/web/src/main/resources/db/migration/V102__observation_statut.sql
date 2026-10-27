-- Statut publié par la source pour cette observation (cancelled, onsale...) : l'événement garde le
-- statut le plus avancé, l'observation permet de voir qu'une billetterie annule une séance
-- que l'autre vend encore.
ALTER TABLE observations ADD COLUMN statut text;
