import { db } from "$lib/server/db";
import { userMatches, concerts } from "@strapontin/core/schema";
import { eq, gte, and, ilike, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const classification = url.searchParams.get("classification")?.trim() ?? "";

  const conditions: SQL[] = [
    eq(userMatches.userId, locals.user!.id),
    gte(concerts.date, aujourdhui),
  ];

  if (recherche) {
    conditions.push(ilike(concerts.artiste, `%${recherche}%`));
  }

  if (mois && /^\d{4}-\d{2}$/.test(mois)) {
    conditions.push(sql`to_char(${concerts.date}, 'YYYY-MM') = ${mois}`);
  }

  if (classification) {
    conditions.push(eq(concerts.classification, classification));
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
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(and(...conditions))
    .orderBy(concerts.date, concerts.artiste)
    .limit(200);

  return { tousLesConcerts, recherche, mois, classification };
};
