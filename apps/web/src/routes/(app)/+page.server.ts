import { db } from "$lib/server/db";
import { userMatches, concerts } from "@strapontin/core/schema";
import { eq, gte, and } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];

  const prochainsConcerts = await db
    .select({
      id: concerts.id,
      artiste: concerts.artiste,
      date: concerts.date,
      heure: concerts.heure,
      salle: concerts.salle,
      ville: concerts.ville,
      url: concerts.url,
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(
      and(
        eq(userMatches.userId, locals.user!.id),
        gte(concerts.date, aujourdhui),
      ),
    )
    .orderBy(concerts.date)
    .limit(20);

  return { prochainsConcerts };
};
