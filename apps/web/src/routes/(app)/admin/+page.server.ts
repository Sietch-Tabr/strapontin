import { db } from "$lib/server/db";
import { redis, CLE_QUOTA_TM } from "$lib/server/redis";
import { auth } from "$lib/auth";
import { users } from "@strapontin/core/schema";
import type { QuotaTM } from "@strapontin/sources";
import { eq } from "drizzle-orm";
import { randomBytes } from "crypto";
import { fail, redirect } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

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
      mustChangeCredentials: users.mustChangeCredentials,
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
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!email) {
      return fail(400, { erreur: "L'email est obligatoire." });
    }

    if (!FORMAT_EMAIL.test(email)) {
      return fail(400, { erreur: "Format d'email invalide." });
    }

    // Nom initial = préfixe de l'email
    const nomInitial = email.split("@")[0].slice(0, 100);

    // Username temporaire unique : u_ + 8 chars aléatoires
    const usernameTemp = "u_" + randomBytes(6).toString("base64url").slice(0, 8);

    // Mot de passe temporaire cryptographiquement sûr
    const motDePasseTemp = randomBytes(12).toString("base64url").slice(0, 16);

    try {
      await auth.api.signUpEmail({
        body: { name: nomInitial, email, password: motDePasseTemp },
      });
    } catch {
      return fail(400, {
        erreur: "Impossible de créer le compte. Email déjà utilisé ?",
      });
    }

    await db
      .update(users)
      .set({
        username: usernameTemp,
        displayUsername: usernameTemp,
        mustChangeCredentials: true,
      })
      .where(eq(users.email, email));

    return { succes: true, motDePasseTemp, email, identifiant: usernameTemp };
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
