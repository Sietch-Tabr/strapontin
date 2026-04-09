import { redirect, fail } from "@sveltejs/kit";
import { auth } from "$lib/auth";
import { db } from "$lib/server/db";
import { users } from "@strapontin/core/schema";
import { count, eq } from "drizzle-orm";
import type { Actions, PageServerLoad } from "./$types";

export const load: PageServerLoad = async () => {
  const [{ valeur }] = await db.select({ valeur: count() }).from(users);

  // Si des utilisateurs existent déjà, rediriger vers le login
  if (valeur > 0) {
    redirect(302, "/login");
  }

  return {};
};

export const actions: Actions = {
  default: async ({ request }) => {
    const [{ valeur }] = await db.select({ valeur: count() }).from(users);

    if (valeur > 0) {
      return fail(403, { erreur: "Un administrateur existe déjà." });
    }

    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const email = donnees.get("email")?.toString().trim() ?? "";
    const motDePasse = donnees.get("motDePasse")?.toString() ?? "";

    if (!nom || !email || !motDePasse) {
      return fail(400, { erreur: "Tous les champs sont obligatoires." });
    }

    if (motDePasse.length < 8) {
      return fail(400, {
        erreur: "Le mot de passe doit contenir au moins 8 caractères.",
      });
    }

    const resultat = await auth.api.signUpEmail({
      body: { name: nom, email, password: motDePasse },
    });

    if (resultat.error) {
      return fail(400, { erreur: "Impossible de créer le compte." });
    }

    // Passer le rôle admin
    await db.update(users).set({ role: "admin" }).where(eq(users.email, email));

    redirect(302, "/login");
  },
};
