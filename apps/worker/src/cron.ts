// Planification des jobs récurrents — toutes les 2h pour chaque user actif.
// Note : BullMQ v5 interdit les ":" dans les jobId — utiliser "__" comme séparateur.

import pino from "pino";
import { Queue } from "bullmq";
import { isNotNull } from "drizzle-orm";
import { userArtistes, userConfig, userSalles } from "@strapontin/core/schema";
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

  // Un seul job batch pour tous les artistes — évite N requêtes TM individuelles
  const nbArtistes = await db.$count(userArtistes);
  if (nbArtistes > 0) {
    await queue.add(
      "fetch:artistes:batch",
      { type: "fetch:artistes:batch" },
      {
        jobId: "artistes__batch",
        removeOnComplete: { count: 10 },
        removeOnFail: true,
      },
    );
  }

  // Récupérer tous les départements configurés (tous users confondus)
  // Note : on ne filtre PAS sur filtreDisponibles — c'est un filtre d'affichage,
  // pas un critère pour savoir si l'user veut des concerts de sa région.
  const configs = await db
    .select({
      departements: userConfig.departements,
      rayonKm: userConfig.rayonKm,
    })
    .from(userConfig);

  const tousLesDepts = new Set<string>();
  for (const config of configs) {
    for (const dept of config.departements) {
      if (dept.trim()) tousLesDepts.add(dept.trim());
    }
  }

  // Rayon maximal parmi tous les users pour couvrir les préférences de chacun
  const maxRayonKm = Math.max(...configs.map((c) => c.rayonKm ?? 50), 50);

  if (tousLesDepts.size > 0) {
    await queue.add(
      "fetch:region",
      {
        type: "fetch:region",
        depts: [...tousLesDepts],
        rayonKm: maxRayonKm,
      },
      {
        jobId: "region__all",
        removeOnComplete: { count: 10 },
        removeOnFail: true,
      },
    );
  }

  // Récupérer toutes les salles avec tmVenueId (pour fetch:salle)
  const sallesAvecVenueId = await db
    .selectDistinct({ tmVenueId: userSalles.tmVenueId })
    .from(userSalles)
    .where(isNotNull(userSalles.tmVenueId));

  for (const { tmVenueId } of sallesAvecVenueId) {
    if (!tmVenueId) continue;
    await queue.add(
      "fetch:salle",
      { type: "fetch:salle", venueId: tmVenueId },
      {
        jobId: `salle__${tmVenueId}`,
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
      jobId: "matches__all",
      delay: 60_000, // 1 min après les fetches
      removeOnComplete: { count: 10 },
      removeOnFail: true,
    },
  );

  const maintenant = new Date();
  const debutJour = new Date(
    maintenant.getFullYear(),
    maintenant.getMonth(),
    maintenant.getDate(),
  );
  const tag = debutJour.toISOString().split("T")[0]!;

  // Purge des concerts passés — une fois par jour à minuit
  const prochainMinuit = new Date(debutJour.getTime() + 24 * 60 * 60 * 1000);
  const delaiPurge = prochainMinuit.getTime() - maintenant.getTime();

  await queue.add(
    "purger:concerts",
    { type: "purger:concerts" },
    {
      jobId: `purge__${tag}`,
      delay: delaiPurge,
      removeOnComplete: { count: 3 },
      removeOnFail: true,
    },
  );

  // Digest quotidien — 8h du matin (heure locale serveur)
  const prochainDigest = new Date(debutJour.getTime() + 8 * 60 * 60 * 1000);
  if (prochainDigest <= maintenant) {
    // 8h déjà passée aujourd'hui → planifier pour demain 8h
    prochainDigest.setDate(prochainDigest.getDate() + 1);
  }
  const delaiDigest = prochainDigest.getTime() - maintenant.getTime();

  await queue.add(
    "notifier:digest",
    { type: "notifier:digest" },
    {
      jobId: `digest__${tag}`,
      delay: delaiDigest,
      removeOnComplete: { count: 3 },
      removeOnFail: true,
    },
  );

  logger.info(
    {
      artistes: nbArtistes,
      depts: tousLesDepts.size,
      salles: sallesAvecVenueId.length,
    },
    "jobs planifiés",
  );
}
