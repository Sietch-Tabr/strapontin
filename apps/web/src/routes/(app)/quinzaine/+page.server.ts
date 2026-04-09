import { db } from "$lib/server/db";
import { userMatches, concerts } from "@strapontin/core/schema";
import { eq, gte, lte, and } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

const SEUIL_NEW_MS = 7 * 24 * 3600 * 1000;

export const load: PageServerLoad = async ({ locals }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const dans15Jours = new Date(Date.now() + 15 * 24 * 60 * 60 * 1000)
    .toISOString()
    .split("T")[0];

  const prochainsConcerts = await db
    .select({
      id: concerts.id,
      artiste: concerts.artiste,
      date: concerts.date,
      heure: concerts.heure,
      salle: concerts.salle,
      ville: concerts.ville,
      url: concerts.url,
      prixMin: concerts.prixMin,
      firstSeen: concerts.firstSeen,
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(
      and(
        eq(userMatches.userId, locals.user!.id),
        gte(concerts.date, aujourdhui),
        lte(concerts.date, dans15Jours),
      ),
    )
    .orderBy(concerts.date, concerts.heure);

  const seuilNew = new Date(Date.now() - SEUIL_NEW_MS);

  return {
    prochainsConcerts: prochainsConcerts.map((c) => ({
      ...c,
      estNew: c.firstSeen ? new Date(c.firstSeen) > seuilNew : false,
    })),
    aujourdhui,
    dans15Jours,
  };
};
