import { db } from "$lib/server/db";
import { userSalles, concerts } from "@strapontin/core/schema";
import { eq, and, ilike, sql } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ locals, url }) => {
  const recherche = url.searchParams.get("q")?.trim() ?? "";

  const sallesSuivies = await db
    .select({ salleId: userSalles.salleId })
    .from(userSalles)
    .where(eq(userSalles.userId, locals.user!.id))
    .orderBy(userSalles.salleId);

  let resultatsRecherche: { salle: string; ville: string | null }[] = [];

  if (recherche.length >= 2) {
    resultatsRecherche = await db
      .selectDistinct({
        salle: concerts.salle,
        ville: concerts.ville,
      })
      .from(concerts)
      .where(ilike(concerts.salle, `%${recherche}%`))
      .orderBy(concerts.salle)
      .limit(20);
  }

  const sallesDejaAjoutees = new Set(sallesSuivies.map((s) => s.salleId));

  return {
    sallesSuivies,
    resultatsRecherche: resultatsRecherche.filter(
      (r) => !sallesDejaAjoutees.has(r.salle),
    ),
    recherche,
  };
};

export const actions: Actions = {
  ajouter: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString().trim() ?? "";

    if (!salleId) {
      return fail(400, { erreur: "Identifiant de salle manquant." });
    }

    const existants = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    if (existants.length > 0) {
      return fail(409, { erreur: "Cette salle est déjà dans votre liste." });
    }

    await db.insert(userSalles).values({
      userId: locals.user!.id,
      salleId,
    });

    return { succes: true };
  },

  supprimer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString() ?? "";

    if (!salleId) {
      return fail(400, { erreur: "Identifiant manquant." });
    }

    await db
      .delete(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    return { succes: true };
  },
};
