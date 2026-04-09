import { db } from "$lib/server/db";
import { auth } from "$lib/auth";
import { users } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { fail, redirect } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ locals }) => {
  if (locals.user?.role !== "admin") {
    redirect(302, "/");
  }

  const tousLesUsers = await db
    .select({
      id: users.id,
      name: users.name,
      email: users.email,
      role: users.role,
      createdAt: users.createdAt,
    })
    .from(users)
    .orderBy(users.createdAt);

  return { utilisateurs: tousLesUsers };
};

export const actions: Actions = {
  creerCompte: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!nom || !email) {
      return fail(400, { erreur: "Nom et email obligatoires." });
    }

    // Générer un mot de passe temporaire
    const motDePasseTemp = Array.from(
      { length: 4 },
      () => Math.random().toString(36).slice(2, 6),
    ).join("-");

    try {
      await auth.api.signUpEmail({
        body: { name: nom, email, password: motDePasseTemp },
      });
    } catch {
      return fail(400, { erreur: "Impossible de créer le compte. Email déjà utilisé ?" });
    }

    return { succes: true, motDePasseTemp, email };
  },

  supprimerCompte: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";

    if (!userId || userId === locals.user.id) {
      return fail(400, { erreur: "Impossible de supprimer ce compte." });
    }

    await db.delete(users).where(eq(users.id, userId));

    return { succes: true };
  },
};
