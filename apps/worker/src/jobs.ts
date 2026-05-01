// Handlers des jobs BullMQ.

import pino from "pino";
import type { Job } from "bullmq";
import type { ConcertBrut, TicketmasterSource } from "@strapontin/sources";
import { resoudreNomCanonique } from "@strapontin/sources";
import { dedoublonner, normaliserTexte } from "@strapontin/core";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import { lt } from "drizzle-orm";
import { db } from "./db.js";
import { obtenirTicketmaster, eventim } from "./sources.js";
import {
  persisterConcerts,
  recalculerMatches,
  notifierUsersQuotidien,
} from "./persister.js";
import {
  redis,
  CLE_QUOTA_TM,
  CLE_HISTORIQUE_QUOTA_TM,
  NB_HISTORIQUE_QUOTA,
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

async function limiterConcurrence<T>(
  taches: (() => Promise<T>)[],
  max: number,
): Promise<PromiseSettledResult<T>[]> {
  const resultats: PromiseSettledResult<T>[] = new Array(taches.length);
  let index = 0;
  async function travailleur() {
    while (index < taches.length) {
      const i = index++;
      try {
        resultats[i] = { status: "fulfilled", value: await taches[i]!() };
      } catch (raison) {
        resultats[i] = { status: "rejected", reason: raison };
      }
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(max, taches.length) }, travailleur),
  );
  return resultats;
}

// Résout les noms d'artistes tout-majuscule via MusicBrainz / Deezer avec cache Redis.
// Appelée après dédoublonnage, avant persistance.
async function resoudreArtistesConcerts(
  concerts: ConcertBrut[],
): Promise<ConcertBrut[]> {
  const nomsAResoudre = [...new Set(concerts.map((c) => c.artiste))];

  if (nomsAResoudre.length === 0) return concerts;

  const tableauResolution = new Map<string, string>();
  let cacheHits = 0;
  let nbMB = 0;
  let nbDeezer = 0;
  let nbInchange = 0;

  for (const nom of nomsAResoudre) {
    const norm = normaliserTexte(nom);
    const cle = cleNomCanonique("artiste", norm);
    const cache = await redis.get(cle).catch(() => null);
    if (cache) {
      tableauResolution.set(nom, cache);
      cacheHits++;
      continue;
    }
    const { nom: nomResolu, source } = await resoudreNomCanonique(
      "artiste",
      nom,
    );
    if (source === "musicbrainz") nbMB++;
    else if (source === "deezer") nbDeezer++;
    else nbInchange++;
    // Cacher même si le nom n'a pas changé — évite les appels MB/Deezer répétés à chaque cron
    await redis
      .setex(cle, TTL_NOM_CANONIQUE_S, nomResolu)
      .catch((err) =>
        logger.warn({ err, nom }, "redis: échec écriture nom canonique"),
      );
    tableauResolution.set(nom, nomResolu);
  }

  logger.info(
    {
      total: nomsAResoudre.length,
      cacheHits,
      musicbrainz: nbMB,
      deezer: nbDeezer,
      inchange: nbInchange,
    },
    "resolver noms canoniques",
  );

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

  let quota: { dispo: number; total: number };
  try {
    quota = JSON.parse(quotaJson) as { dispo: number; total: number };
  } catch {
    logger.warn("redis: quota TM malformé — ignoré");
    return true;
  }

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
  const durationMs = fin.getTime() - debut.getTime();
  const stats: StatsJobRedis = {
    statut: "done",
    debutRun: debut.toISOString(),
    finRun: fin.toISOString(),
    durationMs,
  };
  await redis
    .setex(cleStatsJob(cleType), TTL_STATS_JOB_S, JSON.stringify(stats))
    .catch((err) =>
      logger.warn({ err }, `redis: échec écriture stats job ${cleType}`),
    );
  logger.info({ job: cleType, durationMs }, "durée job");
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

async function sauvegarderQuotaTM(
  tm: TicketmasterSource,
): Promise<number | null> {
  const quota = tm.obtenirQuota();
  if (!quota) return null;
  const payload = JSON.stringify(quota);
  await redis
    .set(CLE_QUOTA_TM, payload)
    .catch((err) => logger.warn({ err }, "redis: échec écriture quota TM"));
  await redis
    .rpush(CLE_HISTORIQUE_QUOTA_TM, payload)
    .then(() => redis.ltrim(CLE_HISTORIQUE_QUOTA_TM, -NB_HISTORIQUE_QUOTA, -1))
    .catch((err) =>
      logger.warn({ err }, "redis: échec écriture historique quota TM"),
    );
  return quota.dispo;
}

async function lireDispoQuotaTM(): Promise<number | null> {
  const json = await redis.get(CLE_QUOTA_TM).catch(() => null);
  if (!json) return null;
  try {
    return (JSON.parse(json) as { dispo: number }).dispo;
  } catch {
    return null;
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

export interface JobNotifierDigest {
  type: "notifier:digest";
}

export type JobData =
  | JobFetchArtiste
  | JobFetchArtistesBatch
  | JobFetchRegion
  | JobFetchSalle
  | JobRecalculerMatches
  | JobPurgerConcerts
  | JobNotifierDigest;

export async function traiterJob(job: Job<JobData>): Promise<void> {
  const { data } = job;

  switch (data.type) {
    case "fetch:artiste": {
      logger.info({ artiste: data.artiste }, "fetch:artiste démarré");

      const tm = await obtenirTicketmaster();
      const [resultsTM, resultsEventim] = await Promise.allSettled([
        tm.fetchArtiste(data.artiste),
        eventim.fetchArtiste(data.artiste),
      ]);

      const tous = [
        ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
        ...(resultsEventim.status === "fulfilled" ? resultsEventim.value : []),
      ];

      if (resultsTM.status === "rejected") {
        logger.warn(
          { err: resultsTM.reason, artiste: data.artiste },
          "TM échoué",
        );
      }
      if (resultsEventim.status === "rejected") {
        logger.warn(
          { err: resultsEventim.reason, artiste: data.artiste },
          "Eventim échoué",
        );
      }

      await sauvegarderQuotaTM(tm);

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
      const tmBatch = await obtenirTicketmaster();
      try {
        if (!(await verifierQuotaTM("fetch:artistes:batch"))) {
          await terminerStatsJob("artistes-batch", debutBatch);
          break;
        }
        const dispoAvantBatch = await lireDispoQuotaTM();

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
          const id = await tmBatch.rechercherAttractionId(nom);
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
          const resultats = await tmBatch.fetchArtistesBatch(lot);
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
          const resultats = await tmBatch.fetchArtiste(artisteAffiche);
          concertsTM.push(...resultats);
          await new Promise((r) => setTimeout(r, 250));
        }

        const dispoApresBatch = await sauvegarderQuotaTM(tmBatch);
        if (dispoAvantBatch !== null && dispoApresBatch !== null) {
          logger.info(
            {
              dispoAvant: dispoAvantBatch,
              dispoApres: dispoApresBatch,
              consommes: dispoAvantBatch - dispoApresBatch,
            },
            "quota TM consommé (artistes:batch)",
          );
        }

        // Eventim : concurrence limitée à 5 pour éviter le bannissement IP
        const nomsArtistes = rows.map((r) => r.artisteAffiche);
        const eventimResults = await limiterConcurrence(
          nomsArtistes.map((nom) => () => eventim.fetchArtiste(nom)),
          5,
        );

        const tous = [
          ...concertsTM,
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
      const tmRegion = await obtenirTicketmaster();
      try {
        if (!(await verifierQuotaTM("fetch:region"))) {
          await terminerStatsJob("region", debutRegion);
          break;
        }
        const dispoAvantRegion = await lireDispoQuotaTM();

        const [resultsTM, resultsEventim] = await Promise.allSettled([
          tmRegion.fetchRegion(data.depts),
          eventim.fetchRegion(data.depts),
        ]);

        const tous = [
          ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
          ...(resultsEventim.status === "fulfilled"
            ? resultsEventim.value
            : []),
        ];

        const dispoApresRegion = await sauvegarderQuotaTM(tmRegion);
        if (dispoAvantRegion !== null && dispoApresRegion !== null) {
          logger.info(
            {
              dispoAvant: dispoAvantRegion,
              dispoApres: dispoApresRegion,
              consommes: dispoAvantRegion - dispoApresRegion,
            },
            "quota TM consommé (region)",
          );
        }

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

      const tmSalle = await obtenirTicketmaster();
      if (!tmSalle.fetchSalle) {
        logger.error("fetchSalle non disponible sur la source TM");
        break;
      }
      const concertsSalle = await tmSalle.fetchSalle(data.venueId);
      logger.info(
        { venueId: data.venueId, total: concertsSalle.length },
        "TM fetchSalle terminé",
      );

      await sauvegarderQuotaTM(tmSalle);

      const dedoublonnes = await resoudreArtistesConcerts(
        dedoublonner(concertsSalle),
      );
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

    case "notifier:digest": {
      logger.info("notifier:digest démarré");
      await notifierUsersQuotidien();
      logger.info("notifier:digest terminé");
      break;
    }

    default: {
      logger.warn({ data }, "type de job inconnu");
    }
  }
}
