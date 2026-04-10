import { db } from "$lib/server/db";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import { eq, gte, and, ilike, inArray, isNotNull, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0]!;
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";

  // Filtre multi-valeur via URL ?genre=Rock&genre=Pop
  const genres = url.searchParams.getAll("genre");
  // Filtre multi-valeur via URL ?classification=music&classification=theatre
  const classifications = url.searchParams.getAll("classification");

  // Départements de l'user (pour pré-cocher)
  const configs = await db
    .select({ departements: userConfig.departements })
    .from(userConfig)
    .where(eq(userConfig.userId, locals.user!.id));
  const userDepts = configs[0]?.departements ?? [];

  // dept_filtre=1 : l'user a interagi explicitement avec les chips dept
  // Sans ce marqueur : première visite → appliquer les depts user par défaut
  const deptFiltre = url.searchParams.has("dept_filtre");
  const deptsUrl = url.searchParams
    .getAll("dept")
    .map((d) => d.trim())
    .filter((d) => /^\d{2,3}$/.test(d));
  const deptsActifs = deptFiltre ? deptsUrl : userDepts;

  const conditions: SQL[] = [
    gte(concerts.date, aujourdhui),
    eq(concerts.disponible, true),
  ];

  if (recherche) {
    conditions.push(ilike(concerts.artiste, `%${recherche}%`));
  }
  if (deptsActifs.length > 0) {
    conditions.push(inArray(concerts.dept, deptsActifs));
  }
  if (mois && /^\d{4}-\d{2}$/.test(mois)) {
    conditions.push(sql`to_char(${concerts.date}, 'YYYY-MM') = ${mois}`);
  }
  if (genres.length > 0) {
    conditions.push(inArray(concerts.genre, genres));
  }
  if (classifications.length > 0) {
    conditions.push(inArray(concerts.classification, classifications));
  }

  const [tousDepts, tousGenres, tousClassifications] = await Promise.all([
    db
      .selectDistinct({ dept: concerts.dept })
      .from(concerts)
      .where(and(gte(concerts.date, aujourdhui), eq(concerts.disponible, true)))
      .orderBy(concerts.dept),
    db
      .selectDistinct({ genre: concerts.genre })
      .from(concerts)
      .where(
        and(
          gte(concerts.date, aujourdhui),
          eq(concerts.disponible, true),
          isNotNull(concerts.genre),
        ),
      )
      .orderBy(concerts.genre),
    db
      .selectDistinct({ classification: concerts.classification })
      .from(concerts)
      .where(
        and(
          gte(concerts.date, aujourdhui),
          eq(concerts.disponible, true),
          isNotNull(concerts.classification),
        ),
      )
      .orderBy(concerts.classification),
  ]);

  const deptsDisponibles = tousDepts
    .map((d) => d.dept?.trim())
    .filter((d): d is string => d !== null && d !== '');

  const genresDisponibles = tousGenres
    .map((g) => g.genre)
    .filter((g): g is string => g !== null);

  const classificationsDisponibles = tousClassifications
    .map((c) => c.classification)
    .filter((c): c is string => c !== null);

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
    mois,
    genres,
    classifications,
    userDepts,
    deptsActifs,
    deptFiltre,
    deptsDisponibles,
    genresDisponibles,
    classificationsDisponibles,
  };
};
