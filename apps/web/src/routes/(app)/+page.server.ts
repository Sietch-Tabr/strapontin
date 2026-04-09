import { db } from "$lib/server/db";
import { userMatches, concerts, userArtistes } from "@strapontin/core/schema";
import { eq, gte, and } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];

  // Uniquement les concerts des artistes suivis (pas les salles)
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
    .innerJoin(
      userArtistes,
      and(
        eq(userArtistes.userId, userMatches.userId),
        eq(userArtistes.artisteNormalise, concerts.artisteNorm),
      ),
    )
    .where(
      and(
        eq(userMatches.userId, locals.user!.id),
        gte(concerts.date, aujourdhui),
      ),
    )
    .orderBy(concerts.date)
    .limit(50);

  // Regrouper par artiste pour détecter les tournées (≥ 2 concerts)
  const groupesMap = new Map<
    string,
    {
      id: string;
      artiste: string;
      date: string;
      heure: string | null;
      salle: string;
      ville: string | null;
      url: string | null;
    }[]
  >();
  for (const concert of prochainsConcerts) {
    const liste = groupesMap.get(concert.artiste) ?? [];
    liste.push(concert);
    groupesMap.set(concert.artiste, liste);
  }

  const artistesGroupes = [...groupesMap.entries()].map(([artiste, dates]) => ({
    artiste,
    estTournee: dates.length >= 2,
    concerts: dates,
  }));

  return { artistesGroupes };
};
