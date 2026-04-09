// Client Redis direct (ioredis) pour le stockage de métriques (quota TM…).
// Distinct de BullMQ qui gère sa propre connexion.

import Redis from "ioredis";

const REDIS_URL = process.env["REDIS_URL"] ?? "redis://localhost:6379";

export const redis = new Redis(REDIS_URL, { lazyConnect: true });

export const CLE_QUOTA_TM = "strapontin:tm:quota";
