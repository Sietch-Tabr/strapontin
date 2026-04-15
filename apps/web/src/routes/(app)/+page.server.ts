import { db } from "$lib/server/db";
import {
  userMatches,
  concerts,
  userArtistes,
  userSalles,
  userConfig,
} from "@strapontin/core/schema";
import { eq, gte, and, count } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

const SEUIL_NEW_MS = 7 * 24 * 3600 * 1000;

export const load: PageServerLoad = async ({ locals }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];

  const config = await db
    .select({ filtreDisponibles: userConfig.filtreDisponibles })
    .from(userConfig)
    .where(eq(userConfig.userId, locals.user!.id))
    .then((rows) => rows[0] ?? null);

  const filtreDisponibles = config?.filtreDisponibles ?? true;

  const conditions: SQL[] = [
    eq(userMatches.userId, locals.user!.id),
    gte(concerts.date, aujourdhui),
  ];

  if (filtreDisponibles) {
    conditions.push(eq(concerts.disponible, true));
  }

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
      firstSeen: concerts.firstSeen,
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
    .where(and(...conditions))
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
      firstSeen: Date | null;
    }[]
  >();
  for (const concert of prochainsConcerts) {
    const liste = groupesMap.get(concert.artiste) ?? [];
    liste.push(concert);
    groupesMap.set(concert.artiste, liste);
  }

  const seuilNew = new Date(Date.now() - SEUIL_NEW_MS);

  const artistesGroupes = [...groupesMap.entries()].map(([artiste, dates]) => ({
    artiste,
    estTournee: dates.length >= 2,
    estNew: dates.some((c) => c.firstSeen && new Date(c.firstSeen) > seuilNew),
    concerts: dates.map((c) => ({
      ...c,
      estNew: c.firstSeen ? new Date(c.firstSeen) > seuilNew : false,
    })),
  }));

  const [{ valeur: nbArtistes }] = await db
    .select({ valeur: count() })
    .from(userArtistes)
    .where(eq(userArtistes.userId, locals.user!.id));

  const [{ valeur: nbSalles }] = await db
    .select({ valeur: count() })
    .from(userSalles)
    .where(eq(userSalles.userId, locals.user!.id));

  return { artistesGroupes, nbArtistes, nbSalles };
};
