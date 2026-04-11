import { Queue } from "bullmq";

const REDIS_URL = process.env["REDIS_URL"] ?? "redis://localhost:6379";

// Parser l'URL Redis pour extraire host/port
function parserRedisUrl(url: string): { host: string; port: number } {
  try {
    const parsed = new URL(url);
    return {
      host: parsed.hostname || "localhost",
      port: parsed.port ? parseInt(parsed.port) : 6379,
    };
  } catch {
    return { host: "localhost", port: 6379 };
  }
}

export const connexionRedis = parserRedisUrl(REDIS_URL);

export const queue = new Queue("concerts", { connection: connexionRedis });
