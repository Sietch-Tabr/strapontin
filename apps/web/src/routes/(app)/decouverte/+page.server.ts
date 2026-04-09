import { db } from "$lib/server/db";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import { eq, gte, and, ilike, inArray, isNotNull, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0]!;
  const recherche = url.searchParams.get("q")?.trim() ?? "";
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const genre = url.searchParams.get("genre")?.trim() ?? "";

  // Départements de l'user (pour pré-cocher)
  const configs = await db
    .select({ departements: userConfig.departements })
    .from(userConfig)
    .where(eq(userConfig.userId, locals.user!.id));
  const userDepts = configs[0]?.departements ?? [];

  // Filtre département : multi-valeur via URL ?dept=33&dept=69
  // Si aucun param URL, on applique les depts de l'user par défaut
  const deptsUrl = url.searchParams
    .getAll("dept")
    .filter((d) => /^\d{2,3}$/.test(d));
  const deptsActifs = deptsUrl.length > 0 ? deptsUrl : userDepts;

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

  if (genre) {
    conditions.push(ilike(concerts.genre, genre));
  }

  // Tous les départements distincts en base (pour le sélecteur)
  const tousDepts = await db
    .selectDistinct({ dept: concerts.dept })
    .from(concerts)
    .where(and(gte(concerts.date, aujourdhui!), eq(concerts.disponible, true)))
    .orderBy(concerts.dept);

  const deptsDisponibles = tousDepts
    .map((d) => d.dept)
    .filter((d): d is string => d !== null);

  const tousGenres = await db
    .selectDistinct({ genre: concerts.genre })
    .from(concerts)
    .where(
      and(
        gte(concerts.date, aujourdhui),
        eq(concerts.disponible, true),
        isNotNull(concerts.genre),
      ),
    )
    .orderBy(concerts.genre);

  const genresDisponibles = tousGenres
    .map((g) => g.genre)
    .filter((g): g is string => g !== null);

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

  // Artistes déjà suivis pour afficher "Suivi"
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
    genre,
    userDepts,
    deptsActifs,
    deptsDisponibles,
    genresDisponibles,
  };
};
