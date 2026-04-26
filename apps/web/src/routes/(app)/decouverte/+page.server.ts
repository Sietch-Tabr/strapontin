import { db } from "$lib/server/db";
import { concerts, userArtistes, userConfig } from "@strapontin/core/schema";
import {
  eq,
  gte,
  lte,
  and,
  ilike,
  inArray,
  isNotNull,
  sql,
  desc,
  asc,
} from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import { queue } from "$lib/server/queue";
import {
  redis,
  cleRegionFetchee,
  TTL_REGION_FETCHEE_S,
  SEUIL_STALENESS_MS,
} from "$lib/server/redis";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0]!;
  const recherche = (url.searchParams.get("q")?.trim() ?? "").slice(0, 100);
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const budgetStr = url.searchParams.get("budget")?.trim() ?? "";
  const budget =
    budgetStr && /^\d+$/.test(budgetStr) ? parseInt(budgetStr) : null;
  const tri =
    url.searchParams.get("tri") === "nouveaute" ? "nouveaute" : "date";

  // Filtre multi-valeur via URL ?genre=Rock&genre=Pop (limité à 20 valeurs)
  const genres = url.searchParams.getAll("genre").slice(0, 20);
  // Filtre multi-valeur via URL ?classification=music&classification=theatre (limité à 20 valeurs)
  const classifications = url.searchParams.getAll("classification").slice(0, 20);

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
  if (budget !== null) {
    conditions.push(lte(concerts.prixMin, String(budget)));
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
    .filter((d): d is string => d !== null && d !== "");

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
      firstSeen: concerts.firstSeen,
    })
    .from(concerts)
    .where(and(...conditions))
    .orderBy(
      ...(tri === "nouveaute"
        ? [desc(concerts.firstSeen), asc(concerts.date)]
        : [asc(concerts.date), asc(concerts.artiste)]),
    )
    .limit(100);

  const artistesSuivis = await db
    .select({ artisteNormalise: userArtistes.artisteNormalise })
    .from(userArtistes)
    .where(eq(userArtistes.userId, locals.user!.id));

  const suivis = new Set(artistesSuivis.map((a) => a.artisteNormalise));

  // Depts actifs hors préférences — lire le timestamp du dernier fetch depuis Redis
  const deptsHorsPrefs = deptsActifs.filter((d) => !userDepts.includes(d));
  const timestampsFetch =
    deptsHorsPrefs.length > 0
      ? await Promise.all(
          deptsHorsPrefs.map((d) =>
            redis.get(cleRegionFetchee(d)).catch(() => null),
          ),
        )
      : [];

  // deptsInfo : pour chaque dept hors prefs, son statut fetch
  const deptsInfo = deptsHorsPrefs.map((dept, i) => {
    const ts = timestampsFetch[i];
    if (!ts) return { dept, fetcheAt: null, stale: true };
    const age = Date.now() - new Date(ts).getTime();
    return { dept, fetcheAt: ts, stale: age > SEUIL_STALENESS_MS };
  });

  // Bannière uniquement pour les depts jamais fetchés ou stales (> 2h)
  const deptsHorsListe = deptsInfo.filter((d) => d.stale).map((d) => d.dept);

  return {
    resultats: resultats.map((c) => ({
      ...c,
      dejaSuivi: suivis.has(c.artisteNorm),
    })),
    recherche,
    mois,
    budget: budgetStr,
    tri,
    genres,
    classifications,
    userDepts,
    deptsActifs,
    deptsHorsListe,
    deptsInfo,
    deptFiltre,
    deptsDisponibles,
    genresDisponibles,
    classificationsDisponibles,
  };
};

export const actions: Actions = {
  // Déclenche un fetch:region immédiat pour les depts sélectionnés, sans modifier les préférences
  fetcherRegion: async ({ locals, request }) => {
    const formData = await request.formData();
    const depts = formData
      .getAll("dept")
      .map(String)
      .filter((d) => /^\d{2,3}$/.test(d));
    if (depts.length === 0)
      return fail(400, { message: "Aucun département sélectionné" });

    const deptsALancer = [...depts].sort();
    await queue
      .add(
        "fetch:region",
        { type: "fetch:region", depts: deptsALancer, userId: locals.user!.id },
        {
          jobId: `region__manuel__${deptsALancer.join("_")}`,
          removeOnComplete: true,
          removeOnFail: true,
        },
      )
      .catch(() => {
        /* worker arrêté en dev */
      });

    // Timestamp immédiat — le job est en queue, données bientôt à jour
    const now = new Date().toISOString();
    await Promise.all(
      depts.map((d) =>
        redis
          .set(cleRegionFetchee(d), now, "EX", TTL_REGION_FETCHEE_S)
          .catch(() => {}),
      ),
    );

    return {
      success: true,
      message: `Actualisation lancée pour ${depts.join(", ")}`,
      jobId: `region__manuel__${deptsALancer.join("_")}`,
    };
  },

  // Ajoute les depts hors liste aux préférences user ET déclenche un fetch:region
  ajouterDepts: async ({ locals, request }) => {
    const formData = await request.formData();
    const depts = formData
      .getAll("dept")
      .map(String)
      .filter((d) => /^\d{2,3}$/.test(d));
    if (depts.length === 0)
      return fail(400, { message: "Aucun département sélectionné" });

    const configs = await db
      .select({ departements: userConfig.departements })
      .from(userConfig)
      .where(eq(userConfig.userId, locals.user!.id));

    const existants = configs[0]?.departements ?? [];
    const nouveaux = [...new Set([...existants, ...depts])].sort();

    await db
      .insert(userConfig)
      .values({ userId: locals.user!.id, departements: nouveaux })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: { departements: nouveaux, updatedAt: new Date() },
      });

    const deptsALancer = [...depts].sort();
    await queue
      .add(
        "fetch:region",
        { type: "fetch:region", depts: deptsALancer, userId: locals.user!.id },
        {
          jobId: `region__manuel__${deptsALancer.join("_")}`,
          removeOnComplete: true,
          removeOnFail: true,
        },
      )
      .catch(() => {
        /* worker arrêté en dev */
      });

    const now = new Date().toISOString();
    await Promise.all(
      depts.map((d) =>
        redis
          .set(cleRegionFetchee(d), now, "EX", TTL_REGION_FETCHEE_S)
          .catch(() => {}),
      ),
    );

    return {
      success: true,
      message: `${depts.join(", ")} ajouté${depts.length > 1 ? "s" : ""} à vos préférences`,
      jobId: `region__manuel__${deptsALancer.join("_")}`,
    };
  },
};
