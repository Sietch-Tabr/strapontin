// Planification des jobs récurrents — toutes les 2h pour chaque user actif.

import pino from "pino";
import { Queue } from "bullmq";
import { eq } from "drizzle-orm";
import { userArtistes, userSalles, userConfig } from "@strapontin/core/schema";
import { db } from "./db.js";
import type { JobData } from "./jobs.js";

const logger = pino({ name: "cron" });

export function creerQueue(connexionRedis: {
  host: string;
  port: number;
}): Queue<JobData> {
  return new Queue<JobData>("concerts", { connection: connexionRedis });
}

export async function planifierJobsUsers(queue: Queue<JobData>): Promise<void> {
  logger.info("planification des jobs utilisateurs");

  // Récupérer tous les artistes suivis (groupés par artiste pour éviter les doublons)
  const artistes = await db
    .selectDistinct({ artisteAffiche: userArtistes.artisteAffiche })
    .from(userArtistes);

  for (const { artisteAffiche } of artistes) {
    await queue.add(
      "fetch:artiste",
      { type: "fetch:artiste", artiste: artisteAffiche },
      {
        jobId: `artiste:${artisteAffiche}`,
        removeOnComplete: { count: 10 },
        removeOnFail: { count: 20 },
      },
    );
  }

  // Récupérer tous les départements configurés (tous users confondus)
  const configs = await db
    .select({
      departements: userConfig.departements,
      userId: userConfig.userId,
    })
    .from(userConfig)
    .where(eq(userConfig.filtreDisponibles, true));

  const tousLesDepts = new Set<string>();
  for (const config of configs) {
    for (const dept of config.departements) {
      tousLesDepts.add(dept);
    }
  }

  if (tousLesDepts.size > 0) {
    await queue.add(
      "fetch:region",
      { type: "fetch:region", depts: [...tousLesDepts] },
      {
        jobId: "region:all",
        removeOnComplete: { count: 10 },
        removeOnFail: { count: 20 },
      },
    );
  }

  // Recalculer les matches après les fetches
  await queue.add(
    "recalculer:matches",
    { type: "recalculer:matches" },
    {
      jobId: "matches:all",
      delay: 60_000, // 1 min après les fetches
      removeOnComplete: { count: 10 },
      removeOnFail: { count: 20 },
    },
  );

  logger.info(
    { artistes: artistes.length, depts: tousLesDepts.size },
    "jobs planifiés",
  );
}
