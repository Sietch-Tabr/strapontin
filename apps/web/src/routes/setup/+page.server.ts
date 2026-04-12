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
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";
    const email = donnees.get("email")?.toString().trim() ?? "";
    const motDePasse = donnees.get("motDePasse")?.toString() ?? "";

    if (!nom || !identifiant || !email || !motDePasse) {
      return fail(400, { erreur: "Tous les champs sont obligatoires." });
    }

    if (identifiant.length < 3 || identifiant.length > 30) {
      return fail(400, {
        erreur: "L'identifiant doit contenir entre 3 et 30 caractères.",
      });
    }

    if (!/^[a-z0-9_]+$/.test(identifiant)) {
      return fail(400, {
        erreur:
          "L'identifiant ne peut contenir que des lettres, chiffres et underscores.",
      });
    }

    if (motDePasse.length < 12) {
      return fail(400, {
        erreur: "Le mot de passe doit contenir au moins 12 caractères.",
      });
    }

    try {
      await auth.api.signUpEmail({
        body: { name: nom, email, password: motDePasse },
      });
    } catch {
      return fail(400, { erreur: "Impossible de créer le compte." });
    }

    // Passer le rôle admin et définir le username
    await db
      .update(users)
      .set({
        role: "admin",
        username: identifiant,
        displayUsername: identifiant,
      })
      .where(eq(users.email, email));

    redirect(302, "/login");
  },
};
