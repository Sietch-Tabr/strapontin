import pino from "pino";
import { Worker } from "bullmq";
import { traiterJob, type JobData } from "./jobs.js";
import { creerQueue, planifierJobsUsers } from "./cron.js";

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
});

worker.on("completed", (job) => {
  logger.info({ jobId: job.id, name: job.name }, "job terminé");
});

worker.on("failed", (job, err) => {
  logger.error(
    { jobId: job?.id, name: job?.name, err: err.message },
    "job échoué",
  );
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
