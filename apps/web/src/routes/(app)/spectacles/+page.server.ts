import { db } from "$lib/server/db";
import {
  userMatches,
  concerts,
  userConfig,
  userArtistes,
  userSalles,
} from "@strapontin/core/schema";
import { eq, gte, lte, and, ilike, isNotNull, sql, inArray } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

const SEUIL_NEW_MS = 7 * 24 * 3600 * 1000;

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const dans15Jours = new Date(Date.now() + 15 * 24 * 60 * 60 * 1000)
    .toISOString()
    .split("T")[0];
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const classification = url.searchParams.get("classification")?.trim() ?? "";
  const genre = url.searchParams.get("genre")?.trim() ?? "";
  const quinzaine = url.searchParams.get("quinzaine") === "1";
  const budgetStr = url.searchParams.get("budget")?.trim() ?? "";
  const budget = budgetStr && /^\d+$/.test(budgetStr) ? parseInt(budgetStr) : null;

  // Sources actives : "artistes" et/ou "salles" — par défaut les deux
  const sourcesUrl = url.searchParams.getAll("source");
  const sourceArtistes = sourcesUrl.length === 0 || sourcesUrl.includes("artistes");
  const sourceSalles = sourcesUrl.length === 0 || sourcesUrl.includes("salles");

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

  if (quinzaine) {
    conditions.push(lte(concerts.date, dans15Jours));
  }
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
  if (budget !== null) {
    conditions.push(lte(concerts.prixMin, String(budget)));
  }

  // Filtre source artistes/salles via sous-requêtes sur userMatches
  if (sourceArtistes && !sourceSalles) {
    const artistesUser = await db
      .select({ artisteNormalise: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(eq(userArtistes.userId, locals.user!.id));
    const norms = artistesUser.map((a) => a.artisteNormalise);
    if (norms.length > 0) {
      conditions.push(inArray(concerts.artisteNorm, norms));
    } else {
      conditions.push(sql`false`);
    }
  } else if (sourceSalles && !sourceArtistes) {
    const sallesUser = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(eq(userSalles.userId, locals.user!.id));
    const ids = sallesUser.map((s) => s.salleId);
    if (ids.length > 0) {
      conditions.push(inArray(concerts.salle, ids));
    } else {
      conditions.push(sql`false`);
    }
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
    quinzaine,
    budget: budgetStr,
    sourceArtistes,
    sourceSalles,
    genresDisponibles,
  };
};
