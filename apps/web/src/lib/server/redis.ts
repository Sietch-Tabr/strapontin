// Client Redis pour la lecture des métriques (quota TM…) depuis le web.

import Redis from "ioredis";

const REDIS_URL = process.env["REDIS_URL"] ?? "redis://localhost:6379";

export const redis = new Redis(REDIS_URL, { lazyConnect: true });

export const CLE_QUOTA_TM = "strapontin:tm:quota";
