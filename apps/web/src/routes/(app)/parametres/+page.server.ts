import { db } from "$lib/server/db";
import { auth } from "$lib/auth";
import { userConfig } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ locals, cookies }) => {
  const config = await db
    .select()
    .from(userConfig)
    .where(eq(userConfig.userId, locals.user!.id))
    .then((rows) => rows[0] ?? null);

  return {
    config,
    theme: cookies.get("theme") ?? "light",
  };
};

export const actions: Actions = {
  config: async ({ request, locals }) => {
    const donnees = await request.formData();

    const depts = (donnees.get("departements")?.toString() ?? "")
      .split(",")
      .map((d) => d.trim())
      .filter((d) => /^\d{2,3}$/.test(d));

    const rayonKm = Math.min(
      500,
      Math.max(0, parseInt(donnees.get("rayonKm")?.toString() ?? "50") || 50),
    );

    const filtreDisponibles =
      donnees.get("filtreDisponibles") === "on" ||
      donnees.get("filtreDisponibles") === "true";

    const notificationsEmail =
      donnees.get("notificationsEmail") === "on" ||
      donnees.get("notificationsEmail") === "true";

    await db
      .insert(userConfig)
      .values({
        userId: locals.user!.id,
        departements: depts,
        rayonKm,
        filtreDisponibles,
        notificationsEmail,
      })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: {
          departements: depts,
          rayonKm,
          filtreDisponibles,
          notificationsEmail,
          updatedAt: new Date(),
        },
      });

    return { succes: "config" };
  },

  theme: async ({ cookies }) => {
    const actuel = cookies.get("theme") ?? "light";
    const nouveau = actuel === "dark" ? "light" : "dark";
    cookies.set("theme", nouveau, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      httpOnly: false,
      sameSite: "lax",
    });
    return { succes: "theme", theme: nouveau };
  },

  compte: async ({ request, locals }) => {
    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";

    if (!nom) {
      return fail(400, {
        erreur: "Le nom ne peut pas être vide.",
        section: "compte",
      });
    }

    if (nom.length > 100) {
      return fail(400, {
        erreur: "Nom trop long (100 caractères max).",
        section: "compte",
      });
    }

    await auth.api.updateUser({
      body: { name: nom },
      headers: request.headers,
    });

    return { succes: "compte" };
  },

  motDePasse: async ({ request }) => {
    const donnees = await request.formData();
    const actuel = donnees.get("motDePasseActuel")?.toString() ?? "";
    const nouveau = donnees.get("motDePasseNouveau")?.toString() ?? "";
    const confirmation =
      donnees.get("motDePasseConfirmation")?.toString() ?? "";

    if (!actuel || !nouveau) {
      return fail(400, {
        erreur: "Tous les champs sont obligatoires.",
        section: "motDePasse",
      });
    }

    if (nouveau.length < 8) {
      return fail(400, {
        erreur: "Le nouveau mot de passe doit contenir au moins 8 caractères.",
        section: "motDePasse",
      });
    }

    if (nouveau !== confirmation) {
      return fail(400, {
        erreur: "Les mots de passe ne correspondent pas.",
        section: "motDePasse",
      });
    }

    try {
      await auth.api.changePassword({
        body: {
          currentPassword: actuel,
          newPassword: nouveau,
          revokeOtherSessions: true,
        },
        headers: request.headers,
      });
    } catch {
      return fail(400, {
        erreur: "Mot de passe actuel incorrect.",
        section: "motDePasse",
      });
    }

    return { succes: "motDePasse" };
  },
};
