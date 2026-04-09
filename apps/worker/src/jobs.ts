// Handlers des jobs BullMQ.

import pino from "pino";
import type { Job } from "bullmq";
import { dedoublonner } from "@strapontin/core";
import { ticketmaster, bandsintown, eventim } from "./sources.js";
import { persisterConcerts, recalculerMatches } from "./persister.js";

const logger = pino({ name: "jobs" });

export interface JobFetchArtiste {
  type: "fetch:artiste";
  artiste: string;
  userId?: string;
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

export type JobData =
  | JobFetchArtiste
  | JobFetchRegion
  | JobFetchSalle
  | JobRecalculerMatches;

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

    case "fetch:region": {
      logger.info({ depts: data.depts }, "fetch:region démarré");

      const [resultsTM, resultsEventim] = await Promise.allSettled([
        ticketmaster.fetchRegion(data.depts),
        eventim.fetchRegion(data.depts),
      ]);

      const tous = [
        ...(resultsTM.status === "fulfilled" ? resultsTM.value : []),
        ...(resultsEventim.status === "fulfilled" ? resultsEventim.value : []),
      ];

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

    default: {
      logger.warn({ data }, "type de job inconnu");
    }
  }
}
