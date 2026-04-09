import { db } from "$lib/server/db";
import { userMatches, concerts, userConfig } from "@strapontin/core/schema";
import { eq, gte, and, ilike, isNotNull, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

const SEUIL_NEW_MS = 7 * 24 * 3600 * 1000;

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const classification = url.searchParams.get("classification")?.trim() ?? "";
  const genre = url.searchParams.get("genre")?.trim() ?? "";

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

  if (recherche) {
    conditions.push(ilike(concerts.artiste, `%${recherche}%`));
  }

  if (mois && /^\d{4}-\d{2}$/.test(mois)) {
    conditions.push(sql`to_char(${concerts.date}, 'YYYY-MM') = ${mois}`);
  }

  if (classification) {
    conditions.push(eq(concerts.classification, classification));
  }

  if (genre) {
    conditions.push(ilike(concerts.genre, genre));
  }

  const tousLesConcerts = await db
    .select({
      id: concerts.id,
      artiste: concerts.artiste,
      date: concerts.date,
      heure: concerts.heure,
      salle: concerts.salle,
      ville: concerts.ville,
      url: concerts.url,
      prixMin: concerts.prixMin,
      prixMax: concerts.prixMax,
      classification: concerts.classification,
      firstSeen: concerts.firstSeen,
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(and(...conditions))
    .orderBy(concerts.date, concerts.artiste)
    .limit(200);

  const seuilNew = new Date(Date.now() - SEUIL_NEW_MS);

  const tousGenres = await db
    .selectDistinct({ genre: concerts.genre })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(
      and(
        eq(userMatches.userId, locals.user!.id),
        gte(concerts.date, aujourdhui),
        isNotNull(concerts.genre),
      ),
    )
    .orderBy(concerts.genre);

  const genresDisponibles = tousGenres
    .map((g) => g.genre)
    .filter((g): g is string => g !== null);

  return {
    tousLesConcerts: tousLesConcerts.map((c) => ({
      ...c,
      estNew: c.firstSeen ? new Date(c.firstSeen) > seuilNew : false,
    })),
    recherche,
    mois,
    classification,
    genre,
    genresDisponibles,
  };
};
