import { db } from "$lib/server/db";
import {
  redis,
  CLE_QUOTA_TM,
  cleStatsJob,
  type StatsJobRedis,
} from "$lib/server/redis";
import { auth } from "$lib/auth";
import { users } from "@strapontin/core/schema";
import type { QuotaTM } from "@strapontin/sources";
import { eq } from "drizzle-orm";
import { randomBytes, createECDH } from "crypto";
import { fail, redirect } from "@sveltejs/kit";
import { envoyerInvitation } from "$lib/server/email";
import { lireConfigsPresentes, sauvegarderConfig } from "$lib/server/config";
import { PUBLIC_BASE_URL } from "$env/static/public";
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

  const [statsArtistesBatchJson, statsRegionJson] = await Promise.all([
    redis.get(cleStatsJob("artistes-batch")).catch(() => null),
    redis.get(cleStatsJob("region")).catch(() => null),
  ]);

  const statsJobs = {
    arristesBatch: statsArtistesBatchJson
      ? (JSON.parse(statsArtistesBatchJson) as StatsJobRedis)
      : null,
    region: statsRegionJson
      ? (JSON.parse(statsRegionJson) as StatsJobRedis)
      : null,
  };

  const configsPresentes = await lireConfigsPresentes();

  const CLES_CONFIG = [
    "TICKETMASTER_API_KEY",
    "RESEND_API_KEY",
    "CONTACT_EMAIL",
    "PUBLIC_BASE_URL",
    "VAPID_PUBLIC_KEY",
    "VAPID_PRIVATE_KEY",
  ] as const;

  const configStatuts = CLES_CONFIG.map((cle) => ({
    cle,
    presente: configsPresentes.has(cle),
  }));

  return { utilisateurs: tousLesUsers, quotaTM, statsJobs, configStatuts };
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
    const usernameTemp =
      "u_" + randomBytes(6).toString("base64url").slice(0, 8);

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

    await envoyerInvitation({
      email,
      identifiant: usernameTemp,
      motDePasse: motDePasseTemp,
    });

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

  reinitialiserMdp: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";

    if (!userId || userId === locals.user.id) {
      return fail(400, { erreur: "Impossible de réinitialiser ce compte." });
    }

    const cible = await db
      .select({ id: users.id, email: users.email, name: users.name })
      .from(users)
      .where(eq(users.id, userId))
      .then((rows) => rows[0] ?? null);

    if (!cible) {
      return fail(404, { erreur: "Utilisateur introuvable." });
    }

    await auth.api.requestPasswordReset({
      body: {
        email: cible.email,
        redirectTo: `${PUBLIC_BASE_URL}/reset-password`,
      },
    });

    return { succes: true, action: "reinitialiserMdp", email: cible.email };
  },

  sauvegarderConfig: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const cle = donnees.get("cle")?.toString().trim() ?? "";
    const valeur = donnees.get("valeur")?.toString().trim() ?? "";

    const CLES_AUTORISEES = new Set([
      "TICKETMASTER_API_KEY",
      "RESEND_API_KEY",
      "CONTACT_EMAIL",
      "PUBLIC_BASE_URL",
      "VAPID_PUBLIC_KEY",
      "VAPID_PRIVATE_KEY",
    ]);

    if (!CLES_AUTORISEES.has(cle)) {
      return fail(400, { erreur: "Clé de configuration invalide." });
    }

    await sauvegarderConfig(cle, valeur);

    return { succes: true, action: "sauvegarderConfig", cle };
  },

  genererVapid: async ({ locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const ecdh = createECDH("prime256v1");
    ecdh.generateKeys();

    await Promise.all([
      sauvegarderConfig("VAPID_PUBLIC_KEY", ecdh.getPublicKey("base64url")),
      sauvegarderConfig("VAPID_PRIVATE_KEY", ecdh.getPrivateKey("base64url")),
    ]);

    return { succes: true, action: "genererVapid" };
  },

  modifierCompte: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const email = donnees.get("email")?.toString().trim().toLowerCase() ?? "";

    if (!userId) {
      return fail(400, { erreur: "Utilisateur invalide." });
    }

    if (nom && nom.length > 100) {
      return fail(400, { erreur: "Nom trop long (100 caractères max)." });
    }

    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return fail(400, { erreur: "Format d'email invalide." });
    }

    const mise: Partial<{ name: string; email: string; updatedAt: Date }> = {
      updatedAt: new Date(),
    };
    if (nom) mise.name = nom;
    if (email) mise.email = email;

    await db.update(users).set(mise).where(eq(users.id, userId));

    return { succes: true, action: "modifierCompte" };
  },
};
