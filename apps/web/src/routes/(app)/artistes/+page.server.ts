import { db } from "$lib/server/db";
import { queue } from "$lib/server/queue";
import { userArtistes } from "@strapontin/core/schema";
import { eq, and } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

function normaliserArtiste(nom: string): string {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export const load: PageServerLoad = async ({ locals }) => {
  const artistes = await db
    .select({
      artisteNormalise: userArtistes.artisteNormalise,
      artisteAffiche: userArtistes.artisteAffiche,
      createdAt: userArtistes.createdAt,
    })
    .from(userArtistes)
    .where(eq(userArtistes.userId, locals.user!.id))
    .orderBy(userArtistes.artisteAffiche);

  return { artistes };
};

export const actions: Actions = {
  ajouter: async ({ request, locals }) => {
    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";

    if (!nom) {
      return fail(400, { erreur: "Le nom de l'artiste est obligatoire." });
    }

    if (nom.length > 200) {
      return fail(400, { erreur: "Nom trop long (200 caractères max)." });
    }

    const normalise = normaliserArtiste(nom);

    const existants = await db
      .select({ artisteNormalise: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(
        and(
          eq(userArtistes.userId, locals.user!.id),
          eq(userArtistes.artisteNormalise, normalise),
        ),
      );

    if (existants.length > 0) {
      return fail(409, { erreur: "Cet artiste est déjà dans votre liste." });
    }

    await db.insert(userArtistes).values({
      userId: locals.user!.id,
      artisteNormalise: normalise,
      artisteAffiche: nom,
    });

    // Déclencher le fetch immédiat pour cet artiste
    await queue
      .add(
        "fetch:artiste",
        { type: "fetch:artiste", artiste: nom, userId: locals.user!.id },
        { removeOnComplete: { count: 10 }, removeOnFail: { count: 20 } },
      )
      .catch(() => {
        /* le worker est peut-être arrêté en dev */
      });

    return { succes: true };
  },

  supprimer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const artisteNormalise = donnees.get("artisteNormalise")?.toString() ?? "";

    if (!artisteNormalise) {
      return fail(400, { erreur: "Identifiant manquant." });
    }

    await db
      .delete(userArtistes)
      .where(
        and(
          eq(userArtistes.userId, locals.user!.id),
          eq(userArtistes.artisteNormalise, artisteNormalise),
        ),
      );

    return { succes: true };
  },
};
