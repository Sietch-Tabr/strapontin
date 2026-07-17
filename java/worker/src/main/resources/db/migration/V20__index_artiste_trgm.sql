-- V20 : index GIN trigram sur concerts.artiste (#930, miroir de V38 web #737)
-- Jeu de migrations dual web/worker : cet index existe déjà côté web (V38) mais n'avait
-- jamais été répliqué côté worker, qui écrit aussi dans concerts.artiste.
-- CREATE EXTENSION répété ici (déjà fait côté web en V12) : les jeux de migrations web/worker
-- sont indépendants, une base neuve provisionnée uniquement via worker (ex. Testcontainers
-- d'un test worker isolé) n'a jamais exécuté les migrations web.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_concerts_artiste_trgm ON concerts USING gin (artiste gin_trgm_ops);
