-- V24 : rattrapage du jeu de migrations worker, jamais synchronisé avec web V4
-- (logs_admin/fetch_jobs — table de statut "dernier fetch" par type_job, distincte de
-- fetch_jobs_historique — créées côté web dès V4 mais jamais reportées côté worker, alors que
-- LogsAdminWorker/WorkerStatsService/NotificationDigestService/WorkerRevocationService/
-- GestionQuotaTM y écrivent tous. Échec SQL silencieux, capté et journalisé en warn, sur toute
-- exécution job dans les tests d'intégration Testcontainers du worker ; sans impact prod, où seul
-- web migre — trouvé en session 2.7.3 en testant #996, même cause que V23).
CREATE TABLE logs_admin (
    id       bigserial                PRIMARY KEY,
    ts       bigint                   NOT NULL,
    niveau   text                     NOT NULL,
    source   text                     NOT NULL,
    message  text                     NOT NULL,
    meta     jsonb
);

CREATE INDEX idx_logs_admin_ts ON logs_admin (ts DESC);

CREATE TABLE fetch_jobs (
    type_job       text                     PRIMARY KEY NOT NULL,
    derniere_exec  timestamp with time zone,
    duree_ms       integer,
    nb_inseres     integer,
    nb_mis_a_jour  integer,
    erreur         text
);
