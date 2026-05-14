// Client Redis pour la lecture des métriques (quota TM…) depuis le web.

import Redis from "ioredis";

const REDIS_URL = process.env["REDIS_URL"] ?? "redis://localhost:6379";

export const redis = new Redis(REDIS_URL, { lazyConnect: true });

export const CLE_QUOTA_TM = "strapontin:tm:quota";

// Clé Redis stockant le timestamp du dernier fetch:region pour un dept.
// Valeur : ISO string. TTL : 8 jours (purge auto si dept abandonné).
export function cleRegionFetchee(dept: string): string {
  return `strapontin:region:fetched:${dept}`;
}
export const TTL_REGION_FETCHEE_S = 8 * 24 * 60 * 60; // 8 jours en secondes
// Seuil en ms au-delà duquel les données d'un dept hors prefs sont considérées stales
export const SEUIL_STALENESS_MS = 2 * 60 * 60 * 1000; // 2h

// Stats des jobs cron — lecture par la page admin
export interface StatsJobRedis {
  statut: "running" | "done" | "error";
  debutRun: string;
  finRun?: string;
  durationMs?: number;
  erreur?: string;
}
export const cleStatsJob = (typeJob: string): string =>
  `strapontin:job:stats:${typeJob}`;

export const CLE_WORKER_LAST_SUCCESS = "strapontin:worker:last_success";
