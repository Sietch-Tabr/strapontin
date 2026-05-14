import { db } from "$lib/server/db";
import {
  redis,
  CLE_QUOTA_TM,
  cleStatsJob,
  CLE_WORKER_LAST_SUCCESS,
  type StatsJobRedis,
} from "$lib/server/redis";
import {
  CLE_LOGS_ADMIN,
  logAdmin,
  type EntreeLogAdmin,
} from "$lib/server/log-admin";
import { auth } from "$lib/auth";
import {
  users,
  userArtistes,
  userConfig,
  sessions,
} from "@strapontin/core/schema";
import type { QuotaTM } from "@strapontin/sources";
import { eq, max } from "drizzle-orm";
import { queue } from "$lib/server/queue";
import { randomBytes, randomUUID, createECDH } from "crypto";
import { fail, redirect } from "@sveltejs/kit";
import { env as envPublic } from "$env/dynamic/public";
import {
  lireConfig,
  lireConfigsPresentes,
  sauvegarderConfig,
} from "$lib/server/config";
import type { PageServerLoad, Actions } from "./$types";
import { FORMAT_EMAIL } from "$lib/constantes/validations";

export const load: PageServerLoad = async ({ locals }) => {
  if (locals.user?.role !== "admin") {
    redirect(302, "/");
  }

  const [tousLesUsers, activites] = await Promise.all([
    db
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
      .orderBy(users.createdAt),
    db
      .select({ userId: sessions.userId, derniere: max(sessions.createdAt) })
      .from(sessions)
      .groupBy(sessions.userId),
  ]);

  const activiteParUser = new Map(activites.map((a) => [a.userId, a.derniere]));
  const utilisateurs = tousLesUsers.map((u) => ({
    ...u,
    derniereActivite: activiteParUser.get(u.id) ?? null,
  }));

  const quotaJson = await redis.get(CLE_QUOTA_TM).catch(() => null);
  const quotaTM: QuotaTM | null = quotaJson ? JSON.parse(quotaJson) : null;

  const [statsArtistesBatchJson, statsRegionJson] = await Promise.all([
    redis.get(cleStatsJob("artistes-batch")).catch(() => null),
    redis.get(cleStatsJob("region")).catch(() => null),
  ]);

  const statsJobs = {
    artistesBatch: statsArtistesBatchJson
      ? (JSON.parse(statsArtistesBatchJson) as StatsJobRedis)
      : null,
    region: statsRegionJson
      ? (JSON.parse(statsRegionJson) as StatsJobRedis)
      : null,
  };

  const [logsRaw, workerLastSuccessRaw] = await Promise.all([
    redis.lrange(CLE_LOGS_ADMIN, 0, 99).catch(() => [] as string[]),
    redis.get(CLE_WORKER_LAST_SUCCESS).catch(() => null),
  ]);
  const logsAdmin: EntreeLogAdmin[] = logsRaw
    .map((s) => {
      try {
        return JSON.parse(s) as EntreeLogAdmin;
      } catch {
        return null;
      }
    })
    .filter((e): e is EntreeLogAdmin => e !== null);

  const configsPresentes = await lireConfigsPresentes();

  const CLES_CONFIG = [
    "TICKETMASTER_API_KEY",
    "RESEND_API_KEY",
    "CONTACT_EMAIL",
    "PUBLIC_BASE_URL",
    "VAPID_PUBLIC_KEY",
    "VAPID_PRIVATE_KEY",
  ] as const;

  const CLES_EN_CLAIR = new Set(["CONTACT_EMAIL", "PUBLIC_BASE_URL"]);

  const configStatuts = await Promise.all(
    CLES_CONFIG.map(async (cle) => ({
      cle,
      presente: configsPresentes.has(cle),
      valeur: CLES_EN_CLAIR.has(cle) ? await lireConfig(cle) : null,
      secret: !CLES_EN_CLAIR.has(cle),
    })),
  );

  return { utilisateurs, quotaTM, statsJobs, configStatuts, logsAdmin, workerLastSuccess: workerLastSuccessRaw };
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

    // Username temporaire : u_ + 8 chars hex (charset [0-9a-f], pas de tiret)
    const usernameTemp = "u_" + randomBytes(4).toString("hex");

    // Mot de passe temporaire cryptographiquement sûr
    const motDePasseTemp = randomBytes(12).toString("base64url").slice(0, 16);

    try {
      await auth.api.signUpEmail({
        body: {
          name: nomInitial,
          email,
          password: motDePasseTemp,
          username: usernameTemp,
          displayUsername: usernameTemp,
        },
      });
    } catch {
      return fail(400, {
        erreur: "Impossible de créer le compte. Email déjà utilisé ?",
      });
    }

    await db
      .update(users)
      .set({ mustChangeCredentials: true })
      .where(eq(users.email, email));

    const baseUrl =
      (await lireConfig("PUBLIC_BASE_URL")) ?? envPublic.PUBLIC_BASE_URL ?? "";
    if (baseUrl) {
      await auth.api.requestPasswordReset({
        body: { email, redirectTo: `${baseUrl}/reset-password` },
      });
    }

    void logAdmin("info", "admin", "compte créé", { email, parAdmin: locals.user.id });
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

    void logAdmin("warn", "admin", "compte supprimé", { userId, parAdmin: locals.user.id });
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

    const baseUrl = (await lireConfig("PUBLIC_BASE_URL")) ?? "";
    if (!baseUrl) {
      return fail(400, {
        erreur:
          "PUBLIC_BASE_URL n'est pas configurée. Le lien de reset serait invalide.",
        action: "reinitialiserMdp",
      });
    }

    await auth.api.requestPasswordReset({
      body: {
        email: cible.email,
        redirectTo: `${baseUrl}/reset-password`,
      },
    });

    void logAdmin("warn", "admin", "mot de passe réinitialisé", { userId: cible.id, parAdmin: locals.user.id });
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

    if (valeur) {
      if (cle === "PUBLIC_BASE_URL") {
        let url: URL;
        try {
          url = new URL(valeur);
        } catch {
          return fail(400, {
            erreur: "Format d'URL invalide.",
            action: "sauvegarderConfig",
            cle,
          });
        }
        const estIp =
          url.hostname === "localhost" ||
          /^(\d{1,3}\.){3}\d{1,3}$/.test(url.hostname) ||
          (/^[0-9a-fA-F:]+$/.test(url.hostname) && url.hostname.includes(":"));
        if (!estIp && url.protocol !== "https:") {
          return fail(400, {
            erreur: "L'URL doit commencer par https:// pour un domaine public.",
            action: "sauvegarderConfig",
            cle,
          });
        }
        try {
          const { lookup } = await import("dns/promises");
          if (!estIp) await lookup(url.hostname);
        } catch {
          return fail(400, {
            erreur: "Le domaine est introuvable (échec de résolution DNS).",
            action: "sauvegarderConfig",
            cle,
          });
        }
      }

      if (cle === "RESEND_API_KEY") {
        if (!/^re_[A-Za-z0-9_-]{10,}$/.test(valeur)) {
          return fail(400, {
            erreur:
              "La clé Resend doit commencer par re_ suivi de caractères alphanumériques.",
            action: "sauvegarderConfig",
            cle,
          });
        }
        try {
          const rep = await fetch("https://api.resend.com/domains", {
            headers: { Authorization: `Bearer ${valeur}` },
            signal: AbortSignal.timeout(5000),
          });
          if (rep.status === 401) {
            return fail(400, {
              erreur: "Clé Resend invalide (accès refusé par l'API).",
              action: "sauvegarderConfig",
              cle,
            });
          }
        } catch {
          // Timeout ou erreur réseau — ne pas bloquer la sauvegarde
        }
      }

      if (cle === "TICKETMASTER_API_KEY") {
        if (!/^[A-Za-z0-9]{16,64}$/.test(valeur)) {
          return fail(400, {
            erreur:
              "Format de clé Ticketmaster invalide (16 à 64 caractères alphanumériques).",
            action: "sauvegarderConfig",
            cle,
          });
        }
        try {
          const rep = await fetch(
            `https://app.ticketmaster.com/discovery/v2/events.json?apikey=${encodeURIComponent(valeur)}&size=1`,
            { signal: AbortSignal.timeout(5000) },
          );
          if (rep.status === 401) {
            return fail(400, {
              erreur: "Clé Ticketmaster invalide (accès refusé par l'API).",
              action: "sauvegarderConfig",
              cle,
            });
          }
        } catch {
          // Timeout ou erreur réseau — ne pas bloquer la sauvegarde
        }
      }
    }

    await sauvegarderConfig(cle, valeur);

    if (cle === "TICKETMASTER_API_KEY" && valeur) {
      const [nbArtistes, configs] = await Promise.all([
        db.$count(userArtistes),
        db
          .select({
            departements: userConfig.departements,
          })
          .from(userConfig),
      ]);

      const correlationIdConfig = randomUUID();

      if (nbArtistes > 0) {
        await queue.add(
          "fetch:artistes:batch",
          { type: "fetch:artistes:batch", correlationId: correlationIdConfig },
          {
            jobId: "artistes__batch",
            removeOnComplete: true,
            removeOnFail: true,
          },
        );
      }

      const tousLesDepts = new Set<string>();
      for (const config of configs) {
        for (const dept of config.departements) {
          if (dept.trim()) tousLesDepts.add(dept.trim());
        }
      }

      if (tousLesDepts.size > 0) {
        await queue.add(
          "fetch:region",
          {
            type: "fetch:region",
            depts: [...tousLesDepts],
            correlationId: correlationIdConfig,
          },
          { jobId: "region__all", removeOnComplete: true, removeOnFail: true },
        );
      }
    }

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

  relancerFetches: async ({ locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const [nbArtistes, configs] = await Promise.all([
      db.$count(userArtistes),
      db
        .select({
          departements: userConfig.departements,
        })
        .from(userConfig),
    ]);

    const correlationIdRelance = randomUUID();

    if (nbArtistes > 0) {
      await queue.add(
        "fetch:artistes:batch",
        { type: "fetch:artistes:batch", correlationId: correlationIdRelance },
        {
          jobId: "artistes__batch",
          removeOnComplete: true,
          removeOnFail: true,
        },
      );
    }

    const tousLesDepts = new Set<string>();
    for (const config of configs) {
      for (const dept of config.departements) {
        if (dept.trim()) tousLesDepts.add(dept.trim());
      }
    }

    if (tousLesDepts.size > 0) {
      await queue.add(
        "fetch:region",
        {
          type: "fetch:region",
          depts: [...tousLesDepts],
          correlationId: correlationIdRelance,
        },
        { jobId: "region__all", removeOnComplete: true, removeOnFail: true },
      );
    }

    return { succes: true, action: "relancerFetches" };
  },

  modifierCompte: async ({ request, locals }) => {
    if (locals.user?.role !== "admin") {
      return fail(403, { erreur: "Accès refusé." });
    }

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const email = donnees.get("email")?.toString().trim().toLowerCase() ?? "";

    if (!userId || userId === locals.user.id) {
      return fail(400, {
        erreur: "Utilisez /parametres pour modifier votre propre compte.",
      });
    }

    if (nom && nom.length > 100) {
      return fail(400, { erreur: "Nom trop long (100 caractères max)." });
    }

    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      return fail(400, { erreur: "Format d'email invalide." });
    }

    if (email) {
      const existant = await db
        .select({ id: users.id })
        .from(users)
        .where(eq(users.email, email))
        .then((r) => r[0] ?? null);
      if (existant && existant.id !== userId) {
        return fail(400, { erreur: "Cette adresse email est déjà utilisée." });
      }
    }

    const mise: Partial<{ name: string; email: string; updatedAt: Date }> = {
      updatedAt: new Date(),
    };
    if (nom) mise.name = nom;
    if (email) mise.email = email;

    await db.update(users).set(mise).where(eq(users.id, userId));

    if (email) {
      void logAdmin("warn", "admin", "email modifié", { userId, nouvelEmail: email, parAdmin: locals.user.id });
    }
    return { succes: true, action: "modifierCompte" };
  },
};
