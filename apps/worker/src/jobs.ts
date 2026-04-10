// Handlers des jobs BullMQ.

import pino from "pino";
import type { Job } from "bullmq";
import type { ConcertBrut } from "@strapontin/sources";
import { dedoublonner } from "@strapontin/core";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import { lt } from "drizzle-orm";
import { db } from "./db.js";
import { ticketmaster, bandsintown, eventim } from "./sources.js";
import { persisterConcerts, recalculerMatches } from "./persister.js";
import {
  redis,
  CLE_QUOTA_TM,
  cleAttractionId,
  TTL_ATTRACTION_ID_S,
  cleRegionFetchee,
  TTL_REGION_FETCHEE_S,
} from "./redis.js";

const logger = pino({ name: "jobs" });

async function sauvegarderQuotaTM(): Promise<void> {
  const quota = ticketmaster.obtenirQuota();
  if (quota) {
    await redis.set(CLE_QUOTA_TM, JSON.stringify(quota)).catch(() => {});
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

      const dedoublonnes = dedoublonner(tous);
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

      // Charger tous les artistes distincts depuis la DB
      const rows = await db
        .selectDistinct({
          artisteAffiche: userArtistes.artisteAffiche,
          artisteNormalise: userArtistes.artisteNormalise,
        })
        .from(userArtistes);

      if (rows.length === 0) break;

      // Résoudre les attraction IDs (Redis cache → TM si absent)
      const idsParNorm = new Map<string, string>();
      const sansId: { nom: string; norm: string }[] = [];

      for (const { artisteAffiche, artisteNormalise } of rows) {
        const cached = await redis
          .get(cleAttractionId(artisteNormalise))
          .catch(() => null);
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
            .catch(() => {});
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
      const artistes_sans_tm = rows.filter(
        (r) => !nomsAvecId.has(r.artisteNormalise),
      );
      for (const { artisteAffiche } of artistes_sans_tm) {
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

      const dedoublonnes = dedoublonner(tous);
      logger.info(
        { bruts: tous.length, dedoublonnes: dedoublonnes.length },
        "fetch:artistes:batch terminé",
      );

      await persisterConcerts(dedoublonnes);
      await recalculerMatches();
      break;
    }

    case "fetch:region": {
      logger.info({ depts: data.depts }, "fetch:region démarré");

      const rayonKmRegion = data.rayonKm ?? 50;
      const [resultsTM, resultsEventim] = await Promise.allSettled([
        ticketmaster.fetchRegion(data.depts, undefined, rayonKmRegion),
        eventim.fetchRegion(data.depts),
      ]);

      const tous = [
        ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
        ...(resultsEventim.status === "fulfilled" ? resultsEventim.value : []),
      ];

      await sauvegarderQuotaTM();

      const dedoublonnes = dedoublonner(tous);
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
            .set(cleRegionFetchee(dept), new Date().toISOString(), "EX", TTL_REGION_FETCHEE_S)
            .catch(() => {}),
        ),
      );

      if (data.userId) {
        await recalculerMatches([data.userId]);
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
        .where(lt(concerts.date, dateLimite));

      logger.info(
        { dateLimite, supprimes: resultat.rowCount ?? 0 },
        "purger:concerts terminé",
      );
      break;
    }

    default: {
      logger.warn({ data }, "type de job inconnu");
    }
  }
}
