// Handlers des jobs BullMQ.

import pino from "pino";
import type { Job } from "bullmq";
import type { ConcertBrut } from "@strapontin/sources";
import { resoudreNomCanonique } from "@strapontin/sources";
import { dedoublonner, normaliserTexte } from "@strapontin/core";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import { lt } from "drizzle-orm";
import { db } from "./db.js";
import { ticketmaster, bandsintown, eventim } from "./sources.js";
import { persisterConcerts, recalculerMatches } from "./persister.js";
import {
  redis,
  CLE_QUOTA_TM,
  cleAttractionId,
  cleNomCanonique,
  TTL_NOM_CANONIQUE_S,
  TTL_ATTRACTION_ID_S,
  cleRegionFetchee,
  TTL_REGION_FETCHEE_S,
  cleStatsJob,
  TTL_STATS_JOB_S,
  type StatsJobRedis,
} from "./redis.js";

const logger = pino({ name: "jobs" });

// Résout les noms d'artistes tout-majuscule via MusicBrainz / Deezer avec cache Redis.
// Appelée après dédoublonnage, avant persistance.
async function resoudreArtistesConcerts(
  concerts: ConcertBrut[],
): Promise<ConcertBrut[]> {
  const nomsAResoudre = [
    ...new Set(
      concerts
        .map((c) => c.artiste)
        .filter((nom) => nom === nom.toUpperCase() && nom.length > 1),
    ),
  ];

  if (nomsAResoudre.length === 0) return concerts;

  const tableauResolution = new Map<string, string>();
  for (const nom of nomsAResoudre) {
    const norm = normaliserTexte(nom);
    const cle = cleNomCanonique("artiste", norm);
    const cache = await redis.get(cle).catch(() => null);
    if (cache) {
      tableauResolution.set(nom, cache);
      continue;
    }
    const resolu = await resoudreNomCanonique("artiste", nom);
    if (resolu !== nom) {
      await redis
        .setex(cle, TTL_NOM_CANONIQUE_S, resolu)
        .catch((err) =>
          logger.warn({ err, nom }, "redis: échec écriture nom canonique"),
        );
    }
    tableauResolution.set(nom, resolu);
  }

  return concerts.map((c) =>
    tableauResolution.has(c.artiste)
      ? { ...c, artiste: tableauResolution.get(c.artiste)! }
      : c,
  );
}

// Seuils de protection quota TM (resets toutes les 24h)
const SEUIL_QUOTA_WARN = 200;
const SEUIL_QUOTA_ABORT = 10;

// Vérifie le quota TM stocké en Redis. Retourne false si le job doit être annulé.
async function verifierQuotaTM(nomJob: string): Promise<boolean> {
  const quotaJson = await redis.get(CLE_QUOTA_TM).catch((err) => {
    logger.warn({ err }, "redis: échec lecture quota TM");
    return null;
  });

  if (!quotaJson) return true; // pas de quota connu → continuer

  const quota = JSON.parse(quotaJson) as { dispo: number; total: number };

  if (quota.dispo < SEUIL_QUOTA_ABORT) {
    logger.error(
      { dispo: quota.dispo, total: quota.total, job: nomJob },
      "quota TM épuisé — job annulé",
    );
    return false;
  }

  if (quota.dispo < SEUIL_QUOTA_WARN) {
    logger.warn(
      { dispo: quota.dispo, total: quota.total, job: nomJob },
      "quota TM faible",
    );
  }

  return true;
}

async function demarrerStatsJob(cleType: string): Promise<Date> {
  const debut = new Date();
  const stats: StatsJobRedis = {
    statut: "running",
    debutRun: debut.toISOString(),
  };
  await redis
    .setex(cleStatsJob(cleType), TTL_STATS_JOB_S, JSON.stringify(stats))
    .catch((err) =>
      logger.warn({ err }, `redis: échec écriture stats job ${cleType}`),
    );
  return debut;
}

async function terminerStatsJob(cleType: string, debut: Date): Promise<void> {
  const fin = new Date();
  const stats: StatsJobRedis = {
    statut: "done",
    debutRun: debut.toISOString(),
    finRun: fin.toISOString(),
    durationMs: fin.getTime() - debut.getTime(),
  };
  await redis
    .setex(cleStatsJob(cleType), TTL_STATS_JOB_S, JSON.stringify(stats))
    .catch((err) =>
      logger.warn({ err }, `redis: échec écriture stats job ${cleType}`),
    );
}

async function echouerStatsJob(
  cleType: string,
  debut: Date,
  err: unknown,
): Promise<void> {
  const stats: StatsJobRedis = {
    statut: "error",
    debutRun: debut.toISOString(),
    finRun: new Date().toISOString(),
    erreur: err instanceof Error ? err.message : String(err),
  };
  await redis
    .setex(cleStatsJob(cleType), TTL_STATS_JOB_S, JSON.stringify(stats))
    .catch((e) =>
      logger.warn({ e }, `redis: échec écriture erreur stats job ${cleType}`),
    );
}

async function sauvegarderQuotaTM(): Promise<void> {
  const quota = ticketmaster.obtenirQuota();
  if (quota) {
    await redis
      .set(CLE_QUOTA_TM, JSON.stringify(quota))
      .catch((err) => logger.warn({ err }, "redis: échec écriture quota TM"));
  }
}

export interface JobFetchArtiste {
  type: "fetch:artiste";
  artiste: string;
  userId?: string;
}

export interface JobFetchArtistesBatch {
  type: "fetch:artistes:batch";
}

export interface JobFetchRegion {
  type: "fetch:region";
  depts: string[];
  userId?: string;
  rayonKm?: number;
}

export interface JobFetchSalle {
  type: "fetch:salle";
  venueId: string;
  userId?: string;
}

export interface JobRecalculerMatches {
  type: "recalculer:matches";
  userIds?: string[];
}

export interface JobPurgerConcerts {
  type: "purger:concerts";
}

export type JobData =
  | JobFetchArtiste
  | JobFetchArtistesBatch
  | JobFetchRegion
  | JobFetchSalle
  | JobRecalculerMatches
  | JobPurgerConcerts;

export async function traiterJob(job: Job<JobData>): Promise<void> {
  const { data } = job;

  switch (data.type) {
    case "fetch:artiste": {
      logger.info({ artiste: data.artiste }, "fetch:artiste démarré");

      const [resultsTM, resultsBIT, resultsEventim] = await Promise.allSettled([
        ticketmaster.fetchArtiste(data.artiste),
        bandsintown.fetchArtiste(data.artiste),
        eventim.fetchArtiste(data.artiste),
      ]);

      const tous = [
        ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
        ...(resultsBIT.status === "fulfilled" ? resultsBIT.value : []),
        ...(resultsEventim.status === "fulfilled" ? resultsEventim.value : []),
      ];

      if (resultsTM.status === "rejected") {
        logger.warn(
          { err: resultsTM.reason, artiste: data.artiste },
          "TM échoué",
        );
      }
      if (resultsBIT.status === "rejected") {
        logger.warn(
          { err: resultsBIT.reason, artiste: data.artiste },
          "BIT échoué",
        );
      }
      if (resultsEventim.status === "rejected") {
        logger.warn(
          { err: resultsEventim.reason, artiste: data.artiste },
          "Eventim échoué",
        );
      }

      await sauvegarderQuotaTM();

      const dedoublonnes = await resoudreArtistesConcerts(dedoublonner(tous));
      logger.info(
        {
          artiste: data.artiste,
          bruts: tous.length,
          dedoublonnes: dedoublonnes.length,
        },
        "dédoublonnage terminé",
      );

      await persisterConcerts(dedoublonnes);

      if (data.userId) {
        await recalculerMatches([data.userId]);
      }
      break;
    }

    case "fetch:artistes:batch": {
      logger.info("fetch:artistes:batch démarré");
      const debutBatch = await demarrerStatsJob("artistes-batch");
      try {
        if (!(await verifierQuotaTM("fetch:artistes:batch"))) {
          await terminerStatsJob("artistes-batch", debutBatch);
          break;
        }

        // Charger tous les artistes distincts depuis la DB
        const rows = await db
          .selectDistinct({
            artisteAffiche: userArtistes.artisteAffiche,
            artisteNormalise: userArtistes.artisteNormalise,
          })
          .from(userArtistes);

        if (rows.length === 0) {
          await terminerStatsJob("artistes-batch", debutBatch);
          break;
        }

        // Résoudre les attraction IDs (Redis cache → TM si absent)
        const idsParNorm = new Map<string, string>();
        const sansId: { nom: string; norm: string }[] = [];

        for (const { artisteAffiche, artisteNormalise } of rows) {
          const cached = await redis
            .get(cleAttractionId(artisteNormalise))
            .catch((err) => {
              logger.warn({ err }, "redis: échec lecture attraction ID");
              return null;
            });
          if (cached) {
            idsParNorm.set(artisteNormalise, cached);
          } else {
            sansId.push({ nom: artisteAffiche, norm: artisteNormalise });
          }
        }

        // Résoudre les IDs manquants via /attractions
        for (const { nom, norm } of sansId) {
          const id = await ticketmaster.rechercherAttractionId(nom);
          if (id) {
            idsParNorm.set(norm, id);
            await redis
              .setex(cleAttractionId(norm), TTL_ATTRACTION_ID_S, id)
              .catch((err) =>
                logger.warn(
                  { err, artisteNorm: norm },
                  "redis: échec écriture attraction ID",
                ),
              );
          }
          await new Promise((r) => setTimeout(r, 250));
        }

        // Fetch TM par lots de 20 attractionIds
        const TAILLE_LOT = 20;
        const tousIds = [...idsParNorm.values()];
        const concertsTM: ConcertBrut[] = [];
        for (let i = 0; i < tousIds.length; i += TAILLE_LOT) {
          const lot = tousIds.slice(i, i + TAILLE_LOT);
          const resultats = await ticketmaster.fetchArtistesBatch(lot);
          concertsTM.push(...resultats);
          if (i + TAILLE_LOT < tousIds.length) {
            await new Promise((r) => setTimeout(r, 250));
          }
        }

        // Fallback : artistes sans ID TM → recherche par keyword
        const nomsAvecId = new Set(
          rows
            .filter((r) => idsParNorm.has(r.artisteNormalise))
            .map((r) => r.artisteNormalise),
        );
        const artistesSansTm = rows.filter(
          (r) => !nomsAvecId.has(r.artisteNormalise),
        );
        for (const { artisteAffiche } of artistesSansTm) {
          const resultats = await ticketmaster.fetchArtiste(artisteAffiche);
          concertsTM.push(...resultats);
          await new Promise((r) => setTimeout(r, 250));
        }

        await sauvegarderQuotaTM();

        // Bandsintown et Eventim : appels parallèles par artiste
        const nomsArtistes = rows.map((r) => r.artisteAffiche);
        const [bitResults, eventimResults] = await Promise.all([
          Promise.allSettled(
            nomsArtistes.map((nom) => bandsintown.fetchArtiste(nom)),
          ),
          Promise.allSettled(
            nomsArtistes.map((nom) => eventim.fetchArtiste(nom)),
          ),
        ]);

        const tous = [
          ...concertsTM,
          ...bitResults
            .filter((r) => r.status === "fulfilled")
            .flatMap((r) => (r as PromiseFulfilledResult<ConcertBrut[]>).value),
          ...eventimResults
            .filter((r) => r.status === "fulfilled")
            .flatMap((r) => (r as PromiseFulfilledResult<ConcertBrut[]>).value),
        ];

        const dedoublonnes = await resoudreArtistesConcerts(dedoublonner(tous));
        logger.info(
          { bruts: tous.length, dedoublonnes: dedoublonnes.length },
          "fetch:artistes:batch terminé",
        );

        await persisterConcerts(dedoublonnes);
        await recalculerMatches();
        await terminerStatsJob("artistes-batch", debutBatch);
      } catch (err) {
        await echouerStatsJob("artistes-batch", debutBatch, err);
        throw err;
      }
      break;
    }

    case "fetch:region": {
      logger.info({ depts: data.depts }, "fetch:region démarré");
      const debutRegion = await demarrerStatsJob("region");
      try {
        if (!(await verifierQuotaTM("fetch:region"))) {
          await terminerStatsJob("region", debutRegion);
          break;
        }

        const rayonKmRegion = data.rayonKm ?? 50;
        const [resultsTM, resultsEventim] = await Promise.allSettled([
          ticketmaster.fetchRegion(data.depts, undefined, rayonKmRegion),
          eventim.fetchRegion(data.depts),
        ]);

        const tous = [
          ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
          ...(resultsEventim.status === "fulfilled"
            ? resultsEventim.value
            : []),
        ];

        await sauvegarderQuotaTM();

        const dedoublonnes = await resoudreArtistesConcerts(dedoublonner(tous));
        logger.info(
          {
            depts: data.depts,
            bruts: tous.length,
            dedoublonnes: dedoublonnes.length,
          },
          "dédoublonnage région terminé",
        );

        await persisterConcerts(dedoublonnes);

        // Enregistrer le timestamp du fetch pour chaque dept (bannière découverte)
        await Promise.all(
          data.depts.map((dept) =>
            redis
              .set(
                cleRegionFetchee(dept),
                new Date().toISOString(),
                "EX",
                TTL_REGION_FETCHEE_S,
              )
              .catch((err) =>
                logger.warn(
                  { err, dept },
                  "redis: échec écriture timestamp région",
                ),
              ),
          ),
        );

        if (data.userId) {
          await recalculerMatches([data.userId]);
        }

        await terminerStatsJob("region", debutRegion);
      } catch (err) {
        await echouerStatsJob("region", debutRegion, err);
        throw err;
      }
      break;
    }

    case "fetch:salle": {
      logger.info({ venueId: data.venueId }, "fetch:salle démarré");

      const concertsSalle = await ticketmaster.fetchSalle!(data.venueId);
      logger.info(
        { venueId: data.venueId, total: concertsSalle.length },
        "TM fetchSalle terminé",
      );

      await sauvegarderQuotaTM();

      const dedoublonnes = dedoublonner(concertsSalle);
      await persisterConcerts(dedoublonnes);

      if (data.userId) {
        await recalculerMatches([data.userId]);
      }
      break;
    }

    case "recalculer:matches": {
      logger.info({ userIds: data.userIds }, "recalculer:matches démarré");
      await recalculerMatches(data.userIds);
      break;
    }

    case "purger:concerts": {
      // Supprimer les concerts dont la date est passée depuis plus de 7 jours
      const limite = new Date();
      limite.setDate(limite.getDate() - 7);
      const dateLimite = limite.toISOString().split("T")[0]!;

      const resultat = await db
        .delete(concerts)
        .where(lt(concerts.date, dateLimite))
        .returning({ id: concerts.id });

      logger.info(
        { dateLimite, supprimes: resultat.length },
        "purger:concerts terminé",
      );
      break;
    }

    default: {
      logger.warn({ data }, "type de job inconnu");
    }
  }
}
