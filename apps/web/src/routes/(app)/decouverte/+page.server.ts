import { db } from "$lib/server/db";
import { concerts, userArtistes } from "@strapontin/core/schema";
import { eq, gte, and, ilike, inArray, sql } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const dept = url.searchParams.get("dept")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";

  const conditions = [
    gte(concerts.date, aujourdhui),
    eq(concerts.disponible, true),
  ];

  if (recherche) {
    conditions.push(ilike(concerts.artiste, `%${recherche}%`));
  }

  if (dept && /^\d{2,3}$/.test(dept)) {
    conditions.push(eq(concerts.dept, dept));
  }

  if (mois && /^\d{4}-\d{2}$/.test(mois)) {
    conditions.push(sql`to_char(${concerts.date}, 'YYYY-MM') = ${mois}`);
  }

  const resultats = await db
    .select({
      id: concerts.id,
      artiste: concerts.artiste,
      artisteNorm: concerts.artisteNorm,
      date: concerts.date,
      heure: concerts.heure,
      salle: concerts.salle,
      ville: concerts.ville,
      dept: concerts.dept,
      url: concerts.url,
      prixMin: concerts.prixMin,
    })
    .from(concerts)
    .where(and(...conditions))
    .orderBy(concerts.date, concerts.artiste)
    .limit(100);

  // Récupérer les artistes déjà suivis par l'user pour indiquer lesquels sont dans la liste
  const artistesSuivis = await db
    .select({ artisteNormalise: userArtistes.artisteNormalise })
    .from(userArtistes)
    .where(eq(userArtistes.userId, locals.user!.id));

  const suivis = new Set(artistesSuivis.map((a) => a.artisteNormalise));

  return {
    resultats: resultats.map((c) => ({
      ...c,
      dejaSuivi: suivis.has(c.artisteNorm),
    })),
    recherche,
    dept,
    mois,
  };
};
