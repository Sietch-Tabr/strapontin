import { db } from "$lib/server/db";
import { redis, CLE_QUOTA_TM } from "$lib/server/redis";
import { auth } from "$lib/auth";
import { users } from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { randomBytes } from "crypto";
import { fail, redirect } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

const FORMAT_IDENTIFIANT = /^[a-z0-9_]+$/;

interface QuotaTM {
  dispo: number;
  total: number;
  reset: number;
  misAJour: string;
}

const FORMAT_EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

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
      username: users.username,
      createdAt: users.createdAt,
    })
    .from(users)
    .orderBy(users.createdAt);

  const quotaJson = await redis.get(CLE_QUOTA_TM).catch(() => null);
  const quotaTM: QuotaTM | null = quotaJson ? JSON.parse(quotaJson) : null;

  return { utilisateurs: tousLesUsers, quotaTM };
};

export const actions: Actions = {
  creerCompte: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!nom || !identifiant || !email) {
      return fail(400, { erreur: "Nom, identifiant et email obligatoires." });
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

    if (!FORMAT_EMAIL.test(email)) {
      return fail(400, { erreur: "Format d'email invalide." });
    }

    // Mot de passe temporaire cryptographiquement sûr
    const motDePasseTemp = randomBytes(12).toString("base64url").slice(0, 16);

    try {
      await auth.api.signUpEmail({
        body: { name: nom, email, password: motDePasseTemp },
      });
    } catch {
      return fail(400, {
        erreur:
          "Impossible de créer le compte. Email ou identifiant déjà utilisé ?",
      });
    }

    await db
      .update(users)
      .set({ username: identifiant, displayUsername: identifiant })
      .where(eq(users.email, email));

    return { succes: true, motDePasseTemp, email, identifiant };
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
