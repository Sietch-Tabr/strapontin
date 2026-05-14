import { db } from "$lib/server/db";
import {
  userMatches,
  concerts,
  userArtistes,
  userSalles,
} from "@strapontin/core/schema";
import { eq, gte, and, count, isNull } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import { error } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";
import {
  lireFiltresStatut,
  construireConditionStatut,
} from "$lib/server/filtres";

export const load: PageServerLoad = async ({ locals, url }) => {
  const aujourdhui = new Date().toISOString().split("T")[0];

  const { inclureIndisponibles } = await lireFiltresStatut(
    url,
    locals.user!.id,
  );

  const conditions: SQL[] = [
    eq(userMatches.userId, locals.user!.id),
    gte(concerts.date, aujourdhui),
    isNull(userMatches.masqueLe),
  ];

  const conditionStatut = construireConditionStatut(inclureIndisponibles);
  if (conditionStatut) conditions.push(conditionStatut);

  const joinUserArtistes = and(
    eq(userArtistes.userId, userMatches.userId),
    eq(userArtistes.artisteNormalise, concerts.artisteNorm),
  );

  const [prochainsConcerts, [{ total: totalConcerts }]] = await Promise.all([
    db
      .select({
        id: concerts.id,
        artiste: concerts.artiste,
        date: concerts.date,
        heure: concerts.heure,
        salle: concerts.salle,
        ville: concerts.ville,
        url: concerts.url,
        statut: concerts.statut,
        notifieLe: userMatches.notifieLe,
      })
      .from(userMatches)
      .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
      .innerJoin(userArtistes, joinUserArtistes)
      .where(and(...conditions))
      .orderBy(concerts.date)
      .limit(50),
    db
      .select({ total: count() })
      .from(userMatches)
      .innerJoin(concerts, eq(userMatches.concertId, concerts.id))
      .innerJoin(userArtistes, joinUserArtistes)
      .where(and(...conditions)),
  ]);

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
      statut: string | null;
      notifieLe: Date | null;
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
    estNew: dates.some((c) => c.notifieLe === null),
    concerts: dates.map((c) => ({
      ...c,
      estNew: c.notifieLe === null,
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

  return {
    artistesGroupes,
    nbArtistes,
    nbSalles,
    inclureIndisponibles,
    totalConcerts,
    tronque: totalConcerts > 50,
  };
};

export const actions: Actions = {
  masquer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const concertId = donnees.get("concertId");
    if (!concertId || typeof concertId !== "string") {
      error(400, "Concert manquant");
    }
    await db
      .update(userMatches)
      .set({ masqueLe: new Date() })
      .where(
        and(
          eq(userMatches.userId, locals.user!.id),
          eq(userMatches.concertId, concertId),
        ),
      );
  },
};
