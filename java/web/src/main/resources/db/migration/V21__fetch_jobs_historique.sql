CREATE TABLE fetch_jobs_historique (
  id            bigserial    PRIMARY KEY,
  type_job      text         NOT NULL,
  exec_le       timestamptz  NOT NULL,
  duree_ms      integer,
  nb_inseres    integer,
  nb_mis_a_jour integer,
  erreur        text
);

CREATE INDEX ON fetch_jobs_historique (exec_le);
