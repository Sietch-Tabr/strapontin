import { redis } from "./redis.js";

export type NiveauLog = "debug" | "info" | "warn" | "error";

export interface EntreeLogAdmin {
  ts: number;
  niveau: NiveauLog;
  source: string;
  message: string;
  meta?: Record<string, unknown>;
}

const CLE_LOGS_ADMIN = "strapontin:admin:logs";
const NB_LOGS_MAX = 100;

export async function logAdmin(
  niveau: NiveauLog,
  source: string,
  message: string,
  meta?: Record<string, unknown>,
): Promise<void> {
  const entree: EntreeLogAdmin = {
    ts: Date.now(),
    niveau,
    source,
    message,
    ...(meta ? { meta } : {}),
  };
  await redis
    .lpush(CLE_LOGS_ADMIN, JSON.stringify(entree))
    .then(() => redis.ltrim(CLE_LOGS_ADMIN, 0, NB_LOGS_MAX - 1))
    .catch(() => undefined);
}
