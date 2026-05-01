// Client Redis direct (ioredis) pour le stockage de métriques (quota TM…).
// Distinct de BullMQ qui gère sa propre connexion.

import Redis from "ioredis";

const REDIS_URL = process.env["REDIS_URL"] ?? "redis://localhost:6379";

export const redis = new Redis(REDIS_URL, { lazyConnect: true });

export const CLE_QUOTA_TM = "strapontin:tm:quota";
export const CLE_HISTORIQUE_QUOTA_TM = "strapontin:tm:quota:historique";
export const NB_HISTORIQUE_QUOTA = 100;

// Cache attraction IDs Ticketmaster — TTL 7 jours
export const TTL_ATTRACTION_ID_S = 7 * 24 * 3600;
export const cleAttractionId = (artisteNorm: string): string =>
  `strapontin:tm:attraction:${artisteNorm}`;

// Timestamp du dernier fetch:region par dept — TTL 8 jours
export const TTL_REGION_FETCHEE_S = 8 * 24 * 60 * 60;
export const cleRegionFetchee = (dept: string): string =>
  `strapontin:region:fetched:${dept}`;

// Noms canoniques résolus via MusicBrainz / Deezer — TTL 7 jours
export const TTL_NOM_CANONIQUE_S = 7 * 24 * 3600;
export const cleNomCanonique = (
  type: "artiste" | "salle",
  norm: string,
): string => `strapontin:nom:canonique:${type}:${norm}`;

// Stats des jobs cron (statut, horodatages, durée, erreur) — TTL 48h
export interface StatsJobRedis {
  statut: "running" | "done" | "error";
  debutRun: string;
  finRun?: string;
  durationMs?: number;
  erreur?: string;
}
export const TTL_STATS_JOB_S = 48 * 3600;
export const cleStatsJob = (typeJob: string): string =>
  `strapontin:job:stats:${typeJob}`;
