// Planification des jobs récurrents — toutes les 2h pour chaque user actif.
// Note : BullMQ v5 interdit les ":" dans les jobId — utiliser "__" comme séparateur.

import { randomUUID } from "crypto";
import pino from "pino";
import { Queue } from "bullmq";
import { isNotNull } from "drizzle-orm";
import { userArtistes, userConfig, userSalles } from "@strapontin/core/schema";
import { db } from "./db.js";
import { lireDispoQuotaTM, SEUIL_QUOTA_CRON } from "./jobs.js";
import type { JobData } from "./jobs.js";

const logger = pino({ name: "cron" });

export function creerQueue(connexionRedis: {
  host: string;
  port: number;
}): Queue<JobData> {
  return new Queue<JobData>("concerts", { connection: connexionRedis });
}

export async function planifierJobsUsers(queue: Queue<JobData>): Promise<void> {
  const correlationId = randomUUID();
  logger.info({ correlationId }, "planification des jobs utilisateurs");

  // Contrôle quota TM : les jobs cron (batch, région) sont suspendus si le quota
  // disponible est inférieur au seuil — les actions utilisateur continuent jusqu'au seuil d'abandon.
  const dispo = await lireDispoQuotaTM();
  const cronAutorise = dispo === null || dispo >= SEUIL_QUOTA_CRON;
  if (!cronAutorise) {
    logger.warn(
      { dispo, seuil: SEUIL_QUOTA_CRON },
      "quota TM faible — jobs cron (batch, région) suspendus",
    );
  }

  // Un seul job batch pour tous les artistes — évite N requêtes TM individuelles
  const nbArtistes = await db.$count(userArtistes);
  if (nbArtistes > 0 && cronAutorise) {
    await queue.add(
      "fetch:artistes:batch",
      { type: "fetch:artistes:batch", correlationId },
      {
        jobId: "artistes__batch",
        removeOnComplete: true,
        removeOnFail: true,
      },
    );
  }

  // Récupérer tous les départements configurés (tous users confondus)
  // Note : on ne filtre PAS sur filtreDisponibles — c'est un filtre d'affichage,
  // pas un critère pour savoir si l'user veut des concerts de sa région.
  const configs = await db
    .select({ departements: userConfig.departements })
    .from(userConfig);

  const tousLesDepts = new Set<string>();
  for (const config of configs) {
    for (const dept of config.departements) {
      if (dept.trim()) tousLesDepts.add(dept.trim());
    }
  }

  if (tousLesDepts.size > 0 && cronAutorise) {
    await queue.add(
      "fetch:region",
      {
        type: "fetch:region",
        depts: [...tousLesDepts],
        correlationId,
      },
      {
        jobId: "region__all",
        removeOnComplete: true,
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
      { type: "fetch:salle", venueId: tmVenueId, correlationId },
      {
        jobId: `salle__${tmVenueId}`,
        removeOnComplete: true,
        removeOnFail: true,
      },
    );
  }

  // Recalculer les matches après les fetches
  await queue.add(
    "recalculer:matches",
    { type: "recalculer:matches", correlationId },
    {
      jobId: "matches__all",
      delay: 60_000,
      removeOnComplete: true,
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
    { type: "purger:concerts", correlationId },
    {
      jobId: `purge__${tag}`,
      delay: delaiPurge,
      removeOnComplete: true,
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
    { type: "notifier:digest", correlationId },
    {
      jobId: `digest__${tag}`,
      delay: delaiDigest,
      removeOnComplete: true,
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
