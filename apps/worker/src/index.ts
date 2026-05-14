import pino from "pino";
import { Worker } from "bullmq";
import { traiterJob, type JobData } from "./jobs.js";
import { creerQueue, planifierJobsUsers } from "./cron.js";
import { logAdmin } from "./log-admin.js";
import { redis, CLE_WORKER_LAST_SUCCESS } from "./redis.js";

const logger = pino({ name: "worker" });

const connexionRedis = {
  host: process.env["REDIS_HOST"] ?? "localhost",
  port: Number(process.env["REDIS_PORT"] ?? 6379),
};

const worker = new Worker<JobData>("concerts", traiterJob, {
  connection: connexionRedis,
  concurrency: 2,
});

worker.on("ready", () => {
  logger.info("worker démarré — en attente de jobs");
  void logAdmin("info", "worker", "worker démarré", {
    pid: process.pid,
    nodeVersion: process.version,
  });
});

worker.on("active", (job) => {
  logger.info({ jobId: job.id, name: job.name }, "job lancé");
  void logAdmin("info", "jobs", `${job.name} lancé`, { jobId: job.id });
});

worker.on("completed", (job) => {
  logger.info({ jobId: job.id, name: job.name }, "job terminé");
  void redis.set(CLE_WORKER_LAST_SUCCESS, new Date().toISOString()).catch(() => undefined);
});

worker.on("failed", (job, err) => {
  logger.error(
    { jobId: job?.id, name: job?.name, err: err.message },
    "job échoué",
  );
  void logAdmin("error", "jobs", `${job?.name ?? "inconnu"} échoué`, {
    jobId: job?.id,
    erreur: err.message,
  });
});

worker.on("error", (err) => {
  logger.error({ err: err.message }, "erreur worker");
});

// Cron : planifier les fetches toutes les 2h
const queue = creerQueue(connexionRedis);

async function lancerCron(): Promise<void> {
  await planifierJobsUsers(queue);
}

// Premier lancement immédiat
lancerCron().catch((err) => logger.error({ err }, "erreur cron initial"));

// Puis toutes les 2h
setInterval(
  () => {
    lancerCron().catch((err) => logger.error({ err }, "erreur cron"));
  },
  2 * 60 * 60 * 1000,
);

// Arrêt propre
process.on("SIGTERM", async () => {
  logger.info("SIGTERM reçu — arrêt du worker");
  await worker.close();
  await queue.close();
  process.exit(0);
});

process.on("SIGINT", async () => {
  logger.info("SIGINT reçu — arrêt du worker");
  await worker.close();
  await queue.close();
  process.exit(0);
});
