import { Worker } from "bullmq";

const connexionRedis = {
  host: process.env.REDIS_HOST ?? "localhost",
  port: Number(process.env.REDIS_PORT ?? 6379),
};

const worker = new Worker(
  "concerts",
  async (job) => {
    console.log("job reçu :", job.name, job.data);
  },
  { connection: connexionRedis },
);

worker.on("ready", () => {
  console.log("worker démarré — en attente de jobs");
});

worker.on("error", (err) => {
  console.error("erreur worker :", err.message);
});

process.on("SIGTERM", async () => {
  await worker.close();
  process.exit(0);
});
