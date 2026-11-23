-- Aucune requête ne peut utiliser cet index : la seule recherche d'artiste sur les événements
-- compare replace(artiste_norm, ' ', ''), une expression qu'il ne couvre pas. Il coûtait 3 Mo et
-- une mise à jour à chaque écriture d'événement. Pour le recréer : voir V61.
DROP INDEX IF EXISTS idx_evenements_artiste_norm_trgm;
