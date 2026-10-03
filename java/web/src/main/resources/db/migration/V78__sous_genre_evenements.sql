-- V78__sous_genre_evenements.sql
--
-- Sous-genre publié par Ticketmaster (troisième niveau de classement, après le segment et le
-- genre). La recherche par département ne demande que certaines classifications : sans ce niveau,
-- elle ne pouvait pas distinguer les événements qu'elle sait renvoyer (comédies musicales) de ceux
-- qu'elle ne renverra jamais (humour, théâtre), et comptait des absences à ces derniers.
ALTER TABLE evenements ADD COLUMN sous_genre text;
