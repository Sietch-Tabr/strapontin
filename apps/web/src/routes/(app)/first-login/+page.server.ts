import { db } from "$lib/server/db";
import { auth } from "$lib/auth";
import { users } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { fail, redirect } from "@sveltejs/kit";
import { logAdmin } from "$lib/server/log-admin";
import type { PageServerLoad, Actions } from "./$types";
import { FORMAT_IDENTIFIANT } from "$lib/constantes/validations";

export const load: PageServerLoad = async ({ locals }) => {
  // Si le flag est déjà à false, l'utilisateur n'a rien à faire ici
  if (!locals.user?.mustChangeCredentials) {
    redirect(302, "/");
  }

  return {};
};

export const actions: Actions = {
  default: async ({ request, locals }) => {
    if (!locals.user?.mustChangeCredentials) {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";
    const motDePasseActuel = donnees.get("motDePasseActuel")?.toString() ?? "";
    const motDePasseNouveau =
      donnees.get("motDePasseNouveau")?.toString() ?? "";
    const motDePasseConfirmation =
      donnees.get("motDePasseConfirmation")?.toString() ?? "";

    if (!nom || !identifiant || !motDePasseActuel || !motDePasseNouveau) {
      return fail(400, { erreur: "Tous les champs sont obligatoires." });
    }

    if (nom.length > 100) {
      return fail(400, { erreur: "Nom trop long (100 caractères max)." });
    }

    if (identifiant.length < 3 || identifiant.length > 30) {
      return fail(400, {
        erreur: "L'identifiant doit contenir entre 3 et 30 caractères.",
      });
    }

    if (!FORMAT_IDENTIFIANT.test(identifiant)) {
      return fail(400, {
        erreur:
          "L'identifiant ne peut contenir que des lettres, chiffres et underscores.",
      });
    }

    if (motDePasseNouveau.length < 12) {
      return fail(400, {
        erreur: "Le mot de passe doit contenir au moins 12 caractères.",
      });
    }

    if (motDePasseNouveau !== motDePasseConfirmation) {
      return fail(400, { erreur: "Les mots de passe ne correspondent pas." });
    }

    // Vérifier l'unicité de l'identifiant
    const existant = await db
      .select({ id: users.id })
      .from(users)
      .where(eq(users.username, identifiant))
      .then((rows) => rows[0] ?? null);

    if (existant && existant.id !== locals.user.id) {
      return fail(400, { erreur: "Cet identifiant est déjà utilisé." });
    }

    // Changer le mot de passe via Better Auth
    try {
      await auth.api.changePassword({
        body: {
          currentPassword: motDePasseActuel,
          newPassword: motDePasseNouveau,
          revokeOtherSessions: true,
        },
        headers: request.headers,
      });
    } catch {
      return fail(400, { erreur: "Mot de passe temporaire incorrect." });
    }

    // Mettre à jour le profil et lever le flag
    await db
      .update(users)
      .set({
        name: nom,
        username: identifiant,
        displayUsername: identifiant,
        mustChangeCredentials: false,
        updatedAt: new Date(),
      })
      .where(eq(users.id, locals.user.id));

    void logAdmin("info", "first-login", "first-login complété", {
      userId: locals.user.id,
    });
    redirect(302, "/");
  },
};
