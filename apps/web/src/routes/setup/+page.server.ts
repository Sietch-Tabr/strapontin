import { createECDH } from "crypto";
import { redirect, fail } from "@sveltejs/kit";
import { auth } from "$lib/auth";
import { db } from "$lib/server/db";
import { users } from "@strapontin/core/schema";
import { lireConfig, sauvegarderConfig } from "$lib/server/config";
import { count, eq } from "drizzle-orm";
import type { Actions, PageServerLoad } from "./$types";

export const load: PageServerLoad = async ({ url }) => {
  const [{ valeur }] = await db.select({ valeur: count() }).from(users);

  if (valeur > 0) {
    redirect(302, "/login");
  }

  return { urlBase: url.origin };
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
    const baseUrl = donnees.get("baseUrl")?.toString().trim() ?? "";

    if (!nom || !identifiant || !email || !motDePasse || !baseUrl) {
      return fail(400, { erreur: "Tous les champs sont obligatoires." });
    }

    try {
      const url = new URL(baseUrl);
      if (!["https:", "http:"].includes(url.protocol)) throw new Error();
    } catch {
      return fail(400, {
        erreur: "L'URL doit être une URL valide (ex : https://mondomaine.fr ou http://192.168.1.10:3000).",
      });
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

    await sauvegarderConfig("PUBLIC_BASE_URL", baseUrl);
    await sauvegarderConfig("CONTACT_EMAIL", email);

    // Générer les clés VAPID si absentes (premier déploiement, pas d'abonnements existants)
    const vapidExistant = await lireConfig("VAPID_PUBLIC_KEY");
    if (!vapidExistant) {
      const ecdh = createECDH("prime256v1");
      ecdh.generateKeys();
      await Promise.all([
        sauvegarderConfig("VAPID_PUBLIC_KEY", ecdh.getPublicKey("base64url")),
        sauvegarderConfig("VAPID_PRIVATE_KEY", ecdh.getPrivateKey("base64url")),
      ]);
    }

    redirect(302, "/login");
  },
};
