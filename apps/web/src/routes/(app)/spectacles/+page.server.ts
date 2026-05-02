import { db } from "$lib/server/db";
import {
  userMatches,
  concerts,
  userArtistes,
  userSalles,
} from "@strapontin/core/schema";
import {
  eq,
  gte,
  lte,
  and,
  ilike,
  isNotNull,
  isNull,
  sql,
  inArray,
  desc,
  asc,
} from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import { error } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";
import {
  lireFiltresStatut,
  construireConditionStatut,
} from "$lib/server/filtres";
import { FORMAT_MOIS } from "$lib/constantes/validations";

const SEUIL_NEW_MS = 7 * 24 * 3600 * 1000;

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];
  const dans15Jours = new Date(Date.now() + 15 * 24 * 60 * 60 * 1000)
    .toISOString()
    .split("T")[0];
  const recherche = (url.searchParams.get("q")?.trim() ?? "").slice(0, 100);
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const classification = url.searchParams.get("classification")?.trim() ?? "";
  const genre = (url.searchParams.get("genre")?.trim() ?? "").slice(0, 50);
  const quinzaine = url.searchParams.get("quinzaine") === "1";
  const tri =
    url.searchParams.get("tri") === "nouveaute" ? "nouveaute" : "date";
  const afficherMasques = url.searchParams.get("masques") === "1";
  const budgetStr = url.searchParams.get("budget")?.trim() ?? "";
  const budget =
    budgetStr && /^\d+$/.test(budgetStr) ? parseInt(budgetStr) : null;

  // Sources actives : "artistes" et/ou "salles" — par défaut les deux
  const sourcesUrl = url.searchParams.getAll("source");
  const sourceArtistes =
    sourcesUrl.length === 0 || sourcesUrl.includes("artistes");
  const sourceSalles = sourcesUrl.length === 0 || sourcesUrl.includes("salles");

  const { inclureIndisponibles } = await lireFiltresStatut(
    url,
    locals.user!.id,
  );

  const conditions: SQL[] = [
    eq(userMatches.userId, locals.user!.id),
    gte(concerts.date, aujourdhui),
    afficherMasques
      ? isNotNull(userMatches.masqueLe)
      : isNull(userMatches.masqueLe),
  ];

  if (quinzaine) {
    conditions.push(lte(concerts.date, dans15Jours));
  }
  const conditionStatut = construireConditionStatut(inclureIndisponibles);
  if (conditionStatut) conditions.push(conditionStatut);
  if (recherche) {
    conditions.push(ilike(concerts.artiste, `%${recherche}%`));
  }
  if (mois && FORMAT_MOIS.test(mois)) {
    conditions.push(sql`to_char(${concerts.date}, 'YYYY-MM') = ${mois}`);
  }
  if (classification) {
    conditions.push(eq(concerts.classification, classification));
  }
  if (genre) {
    conditions.push(ilike(concerts.genre, genre));
  }
  if (budget !== null) {
    conditions.push(sql`${concerts.prixMin}::numeric <= ${budget}`);
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
      statut: concerts.statut,
    })
    .from(userMatches)
    .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
    .where(and(...conditions))
    .orderBy(
      ...(tri === "nouveaute"
        ? [desc(concerts.firstSeen), asc(concerts.date)]
        : [asc(concerts.date), asc(concerts.artiste)]),
    )
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
    tri,
    budget: budgetStr,
    sourceArtistes,
    sourceSalles,
    genresDisponibles,
    afficherMasques,
    inclureIndisponibles,
  };
};

export const actions: Actions = {
  demasquer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const concertId = donnees.get("concertId");
    if (!concertId || typeof concertId !== "string") {
      error(400, "Concert manquant");
    }
    await db
      .update(userMatches)
      .set({ masqueLe: null })
      .where(
        and(
          eq(userMatches.userId, locals.user!.id),
          eq(userMatches.concertId, concertId),
        ),
      );
  },
};
