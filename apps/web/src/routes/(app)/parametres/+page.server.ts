import { db } from "$lib/server/db";
import { auth } from "$lib/auth";
import {
  userConfig,
  userArtistes,
  userSalles,
  users,
} from "@strapontin/core/schema";
import { eq } from "drizzle-orm";
import { fail, redirect } from "@sveltejs/kit";
import { queue } from "$lib/server/queue";
import { resendDisponible } from "$lib/server/email";
import type { PageServerLoad, Actions } from "./$types";
import {
  FORMAT_IDENTIFIANT,
  FORMAT_EMAIL,
  FORMAT_DEPT,
} from "$lib/constantes/validations";

// Normalise un code département saisi par l'utilisateur.
// Accepte les formats standards (ex. "75", "971") et la Corse en codes
// administratifs (2A/2B), qu'on ramène à "20" car TM et Eventim dérivent
// le département depuis le code postal (20xxx → "20").
function normaliserDept(d: string): string | null {
  const valeur = d.trim().toUpperCase();
  if (/^2[AB]$/.test(valeur)) return "20";
  if (FORMAT_DEPT.test(valeur)) return valeur;
  return null;
}

interface ArtisteExporte {
  nom: string;
  normalise: string;
}

interface SalleExportee {
  salleId: string;
  tmVenueId: string | null;
}

interface ConfigExportee {
  departements?: string[];
  filtreDisponibles?: boolean;
  frequenceNotifications?: string;
  notifArtistes?: boolean;
  notifSalles?: boolean;
  theme?: string;
}

export const load: PageServerLoad = async ({ locals, cookies }) => {
  const config = await db
    .select()
    .from(userConfig)
    .where(eq(userConfig.userId, locals.user!.id))
    .then((rows) => rows[0] ?? null);

  return {
    config,
    theme: (cookies.get("theme") ?? "auto") as "auto" | "clair" | "sombre",
    resendDisponible: await resendDisponible(),
  };
};

export const actions: Actions = {
  departements: async ({ request, locals }) => {
    const donnees = await request.formData();

    const depts = (donnees.get("departements")?.toString() ?? "")
      .split(",")
      .map((d) => normaliserDept(d))
      .filter((d): d is string => d !== null);

    await db
      .insert(userConfig)
      .values({
        userId: locals.user!.id,
        departements: depts,
      })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: { departements: depts, updatedAt: new Date() },
      });

    return { succes: "departements" };
  },

  rechercheRapide: async ({ request, locals }) => {
    const donnees = await request.formData();

    const filtreDisponibles =
      donnees.get("filtreDisponibles") === "on" ||
      donnees.get("filtreDisponibles") === "true";

    await db
      .insert(userConfig)
      .values({
        userId: locals.user!.id,
        filtreDisponibles,
      })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: { filtreDisponibles, updatedAt: new Date() },
      });

    return { succes: "rechercheRapide" };
  },

  notifications: async ({ request, locals }) => {
    const donnees = await request.formData();
    const valeur = donnees.get("frequenceNotifications")?.toString();
    const valeurs = ["jamais", "temps_reel", "quotidien"] as const;
    type FrequenceNotifications = (typeof valeurs)[number];

    if (!valeurs.includes(valeur as FrequenceNotifications)) {
      return fail(400, {
        erreur: "Valeur invalide.",
        section: "notifications",
      });
    }

    const frequenceNotifications = valeur as FrequenceNotifications;
    const notifArtistes = donnees.get("notifArtistes") === "on";
    const notifSalles = donnees.get("notifSalles") === "on";

    await db
      .insert(userConfig)
      .values({
        userId: locals.user!.id,
        frequenceNotifications,
        notifArtistes,
        notifSalles,
      })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: {
          frequenceNotifications,
          notifArtistes,
          notifSalles,
          updatedAt: new Date(),
        },
      });

    return { succes: "notifications" };
  },

  theme: async ({ request, cookies, locals }) => {
    const donnees = await request.formData();
    const valeur = donnees.get("valeur")?.toString();

    if (!["auto", "clair", "sombre"].includes(valeur ?? "")) {
      return fail(400, {
        erreur: "Valeur de thème invalide.",
        section: "theme",
      });
    }

    const theme = valeur as "auto" | "clair" | "sombre";
    cookies.set("theme", theme, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      httpOnly: false,
      secure: true,
      sameSite: "lax",
    });

    await db
      .insert(userConfig)
      .values({ userId: locals.user!.id, theme })
      .onConflictDoUpdate({
        target: userConfig.userId,
        set: { theme, updatedAt: new Date() },
      });

    return { succes: "theme", theme };
  },

  compte: async ({ request }) => {
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

  identifiant: async ({ request, locals }) => {
    const donnees = await request.formData();
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";

    if (!identifiant) {
      return fail(400, {
        erreur: "L'identifiant ne peut pas être vide.",
        section: "identifiant",
      });
    }

    if (identifiant.length < 3 || identifiant.length > 30) {
      return fail(400, {
        erreur: "L'identifiant doit contenir entre 3 et 30 caractères.",
        section: "identifiant",
      });
    }

    if (!FORMAT_IDENTIFIANT.test(identifiant)) {
      return fail(400, {
        erreur:
          "L'identifiant ne peut contenir que des lettres, chiffres et underscores.",
        section: "identifiant",
      });
    }

    const existant = await db
      .select({ id: users.id })
      .from(users)
      .where(eq(users.username, identifiant))
      .then((rows) => rows[0] ?? null);

    if (existant && existant.id !== locals.user!.id) {
      return fail(400, {
        erreur: "Cet identifiant est déjà utilisé.",
        section: "identifiant",
      });
    }

    await db
      .update(users)
      .set({
        username: identifiant,
        displayUsername: identifiant,
        updatedAt: new Date(),
      })
      .where(eq(users.id, locals.user!.id));

    return { succes: "identifiant" };
  },

  email: async ({ request, locals }) => {
    const donnees = await request.formData();
    const email = donnees.get("email")?.toString().trim().toLowerCase() ?? "";

    if (!email) {
      return fail(400, {
        erreur: "L'adresse email ne peut pas être vide.",
        section: "email",
      });
    }

    if (!FORMAT_EMAIL.test(email)) {
      return fail(400, {
        erreur: "Format d'email invalide.",
        section: "email",
      });
    }

    if (email.length > 254) {
      return fail(400, {
        erreur: "Adresse email trop longue.",
        section: "email",
      });
    }

    const existant = await db
      .select({ id: users.id })
      .from(users)
      .where(eq(users.email, email))
      .then((rows) => rows[0] ?? null);

    if (existant && existant.id !== locals.user!.id) {
      return fail(400, {
        erreur: "Cette adresse email est déjà utilisée.",
        section: "email",
      });
    }

    await db
      .update(users)
      .set({ email, updatedAt: new Date() })
      .where(eq(users.id, locals.user!.id));

    return { succes: "email" };
  },

  supprimerCompte: async ({ locals, cookies }) => {
    const userId = locals.user!.id;

    await db.delete(users).where(eq(users.id, userId));

    cookies.delete("theme", { path: "/" });

    redirect(302, "/login");
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

    if (nouveau.length < 12) {
      return fail(400, {
        erreur: "Le nouveau mot de passe doit contenir au moins 12 caractères.",
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

  importerPreferences: async ({ request, locals, cookies }) => {
    const donnees = await request.formData();
    const fichier = donnees.get("fichier");
    const mode =
      donnees.get("mode")?.toString() === "ecraser" ? "ecraser" : "merger";
    const importerConfig = donnees.get("importerConfig") === "on";

    if (!fichier || typeof fichier === "string" || fichier.size === 0) {
      return fail(400, {
        erreur: "Aucun fichier sélectionné.",
        section: "import",
      });
    }

    if (fichier.size > 1_000_000) {
      return fail(400, {
        erreur: "Fichier trop volumineux (1 Mo maximum).",
        section: "import",
      });
    }

    // Lecture et validation JSON
    let contenu: unknown;
    try {
      contenu = JSON.parse(await fichier.text());
    } catch {
      return fail(400, { erreur: "Fichier JSON invalide.", section: "import" });
    }

    if (
      typeof contenu !== "object" ||
      contenu === null ||
      !Array.isArray((contenu as Record<string, unknown>).artistes) ||
      !Array.isArray((contenu as Record<string, unknown>).salles)
    ) {
      return fail(400, {
        erreur: "Format non reconnu (artistes et salles attendus).",
        section: "import",
      });
    }

    const donneesParsees = contenu as {
      artistes: ArtisteExporte[];
      salles: SalleExportee[];
      config?: ConfigExportee;
    };

    const artistesValides = donneesParsees.artistes.filter(
      (a) =>
        typeof a.nom === "string" &&
        typeof a.normalise === "string" &&
        a.nom.trim() &&
        a.nom.length <= 200 &&
        a.normalise.length <= 200,
    );
    const sallesValides = donneesParsees.salles.filter(
      (s) =>
        typeof s.salleId === "string" &&
        s.salleId.trim() &&
        s.salleId.length <= 200 &&
        (!s.tmVenueId || /^[A-Za-z0-9]{1,30}$/.test(s.tmVenueId)),
    );

    if (artistesValides.length > 500) {
      return fail(400, {
        erreur: "Le fichier contient trop d'artistes (500 maximum).",
        section: "import",
      });
    }

    if (sallesValides.length > 200) {
      return fail(400, {
        erreur: "Le fichier contient trop de salles (200 maximum).",
        section: "import",
      });
    }

    const userId = locals.user!.id;
    let sallesInsereesAvecVenueId: SalleExportee[] = [];
    let deptsNouveaux: string[] = [];

    try {
      if (mode === "ecraser") {
        // Transaction : suppression + réinsertion atomiques
        await db.transaction(async (tx) => {
          await tx.delete(userArtistes).where(eq(userArtistes.userId, userId));
          await tx.delete(userSalles).where(eq(userSalles.userId, userId));

          if (artistesValides.length > 0) {
            await tx.insert(userArtistes).values(
              artistesValides.map((a) => ({
                userId,
                artisteAffiche: a.nom.trim(),
                artisteNormalise: a.normalise.trim(),
              })),
            );
          }
          if (sallesValides.length > 0) {
            await tx.insert(userSalles).values(
              sallesValides.map((s) => ({
                userId,
                salleId: s.salleId.trim(),
                tmVenueId: s.tmVenueId ?? null,
              })),
            );
          }

          if (importerConfig && donneesParsees.config) {
            const cfg = donneesParsees.config;
            const depts = (cfg.departements ?? [])
              .map((d) => normaliserDept(String(d)))
              .filter((d): d is string => d !== null);

            // Récupérer les depts existants pour détecter les nouveaux
            const configActuelle = await tx
              .select({ departements: userConfig.departements })
              .from(userConfig)
              .where(eq(userConfig.userId, userId))
              .then((rows) => rows[0] ?? null);
            const deptsExistants = new Set(configActuelle?.departements ?? []);
            deptsNouveaux = depts.filter((d) => !deptsExistants.has(d));

            const themeImporte = ["auto", "clair", "sombre"].includes(
              cfg.theme ?? "",
            )
              ? (cfg.theme as "auto" | "clair" | "sombre")
              : undefined;

            const notifArtistesImporte =
              cfg.notifArtistes !== undefined
                ? Boolean(cfg.notifArtistes)
                : undefined;
            const notifSallesImporte =
              cfg.notifSalles !== undefined
                ? Boolean(cfg.notifSalles)
                : undefined;

            await tx
              .insert(userConfig)
              .values({
                userId,
                departements: depts,
                filtreDisponibles: cfg.filtreDisponibles ?? true,
                frequenceNotifications: ([
                  "jamais",
                  "temps_reel",
                  "quotidien",
                ].includes(cfg.frequenceNotifications ?? "")
                  ? cfg.frequenceNotifications
                  : "jamais") as "jamais" | "temps_reel" | "quotidien",
                ...(notifArtistesImporte !== undefined
                  ? { notifArtistes: notifArtistesImporte }
                  : {}),
                ...(notifSallesImporte !== undefined
                  ? { notifSalles: notifSallesImporte }
                  : {}),
                ...(themeImporte ? { theme: themeImporte } : {}),
              })
              .onConflictDoUpdate({
                target: userConfig.userId,
                set: {
                  departements: depts,
                  filtreDisponibles: cfg.filtreDisponibles ?? true,
                  frequenceNotifications: ([
                    "jamais",
                    "temps_reel",
                    "quotidien",
                  ].includes(cfg.frequenceNotifications ?? "")
                    ? cfg.frequenceNotifications
                    : "jamais") as "jamais" | "temps_reel" | "quotidien",
                  ...(notifArtistesImporte !== undefined
                    ? { notifArtistes: notifArtistesImporte }
                    : {}),
                  ...(notifSallesImporte !== undefined
                    ? { notifSalles: notifSallesImporte }
                    : {}),
                  ...(themeImporte ? { theme: themeImporte } : {}),
                  updatedAt: new Date(),
                },
              });

            if (themeImporte) {
              cookies.set("theme", themeImporte, {
                path: "/",
                maxAge: 60 * 60 * 24 * 365,
                httpOnly: false,
                secure: true,
                sameSite: "lax",
              });
            }
          }
        });

        sallesInsereesAvecVenueId = sallesValides.filter((s) => s.tmVenueId);
      } else {
        // Merger : lire les existants, insérer uniquement les absents
        const [artistesExistants, sallesExistantes] = await Promise.all([
          db
            .select({ artisteNormalise: userArtistes.artisteNormalise })
            .from(userArtistes)
            .where(eq(userArtistes.userId, userId)),
          db
            .select({ salleId: userSalles.salleId })
            .from(userSalles)
            .where(eq(userSalles.userId, userId)),
        ]);

        const normsExistants = new Set(
          artistesExistants.map((a) => a.artisteNormalise),
        );
        const sallesExistantesIds = new Set(
          sallesExistantes.map((s) => s.salleId),
        );

        const artistesNouveaux = artistesValides.filter(
          (a) => !normsExistants.has(a.normalise.trim()),
        );
        const sallesNouvelles = sallesValides.filter(
          (s) => !sallesExistantesIds.has(s.salleId.trim()),
        );

        if (artistesExistants.length + artistesNouveaux.length > 500) {
          return fail(400, {
            erreur: `Import impossible : vous avez déjà ${artistesExistants.length} artiste${artistesExistants.length > 1 ? "s" : ""}, l'ajout de ${artistesNouveaux.length} dépasserait la limite de 500.`,
            section: "import",
          });
        }
        if (sallesExistantes.length + sallesNouvelles.length > 200) {
          return fail(400, {
            erreur: `Import impossible : vous avez déjà ${sallesExistantes.length} salle${sallesExistantes.length > 1 ? "s" : ""}, l'ajout de ${sallesNouvelles.length} dépasserait la limite de 200.`,
            section: "import",
          });
        }

        await db.transaction(async (tx) => {
          if (artistesNouveaux.length > 0) {
            await tx.insert(userArtistes).values(
              artistesNouveaux.map((a) => ({
                userId,
                artisteAffiche: a.nom.trim(),
                artisteNormalise: a.normalise.trim(),
              })),
            );
          }
          if (sallesNouvelles.length > 0) {
            await tx.insert(userSalles).values(
              sallesNouvelles.map((s) => ({
                userId,
                salleId: s.salleId.trim(),
                tmVenueId: s.tmVenueId ?? null,
              })),
            );
          }

          if (importerConfig && donneesParsees.config) {
            const cfg = donneesParsees.config;
            const depts = (cfg.departements ?? [])
              .map((d) => normaliserDept(String(d)))
              .filter((d): d is string => d !== null);

            const configActuelle = await tx
              .select({ departements: userConfig.departements })
              .from(userConfig)
              .where(eq(userConfig.userId, userId))
              .then((rows) => rows[0] ?? null);
            const deptsExistants = new Set(configActuelle?.departements ?? []);
            // En mode merger, on fusionne les depts (union)
            const tousLesDepts = [
              ...new Set([...deptsExistants, ...depts]),
            ].sort();
            deptsNouveaux = depts.filter((d) => !deptsExistants.has(d));

            const themeImporte = ["auto", "clair", "sombre"].includes(
              cfg.theme ?? "",
            )
              ? (cfg.theme as "auto" | "clair" | "sombre")
              : undefined;

            const notifArtistesImporte2 =
              cfg.notifArtistes !== undefined
                ? Boolean(cfg.notifArtistes)
                : undefined;
            const notifSallesImporte2 =
              cfg.notifSalles !== undefined
                ? Boolean(cfg.notifSalles)
                : undefined;

            await tx
              .insert(userConfig)
              .values({
                userId,
                departements: tousLesDepts,
                filtreDisponibles: cfg.filtreDisponibles ?? true,
                frequenceNotifications: ([
                  "jamais",
                  "temps_reel",
                  "quotidien",
                ].includes(cfg.frequenceNotifications ?? "")
                  ? cfg.frequenceNotifications
                  : "jamais") as "jamais" | "temps_reel" | "quotidien",
                ...(notifArtistesImporte2 !== undefined
                  ? { notifArtistes: notifArtistesImporte2 }
                  : {}),
                ...(notifSallesImporte2 !== undefined
                  ? { notifSalles: notifSallesImporte2 }
                  : {}),
                ...(themeImporte ? { theme: themeImporte } : {}),
              })
              .onConflictDoUpdate({
                target: userConfig.userId,
                set: {
                  departements: tousLesDepts,
                  filtreDisponibles: cfg.filtreDisponibles ?? true,
                  frequenceNotifications: ([
                    "jamais",
                    "temps_reel",
                    "quotidien",
                  ].includes(cfg.frequenceNotifications ?? "")
                    ? cfg.frequenceNotifications
                    : "jamais") as "jamais" | "temps_reel" | "quotidien",
                  ...(notifArtistesImporte2 !== undefined
                    ? { notifArtistes: notifArtistesImporte2 }
                    : {}),
                  ...(notifSallesImporte2 !== undefined
                    ? { notifSalles: notifSallesImporte2 }
                    : {}),
                  ...(themeImporte ? { theme: themeImporte } : {}),
                  updatedAt: new Date(),
                },
              });

            if (themeImporte) {
              cookies.set("theme", themeImporte, {
                path: "/",
                maxAge: 60 * 60 * 24 * 365,
                httpOnly: false,
                secure: true,
                sameSite: "lax",
              });
            }
          }
        });

        sallesInsereesAvecVenueId = sallesNouvelles.filter((s) => s.tmVenueId);
      }
    } catch {
      return fail(500, {
        erreur:
          "Une erreur est survenue lors de l'import. Aucune donnée n'a été modifiée.",
        section: "import",
      });
    }

    // Fetch post-import — en dehors de la transaction (erreurs tolérées)
    await queue
      .add(
        "fetch:artistes:batch",
        { type: "fetch:artistes:batch" },
        {
          jobId: "artistes__batch",
          removeOnComplete: true,
          removeOnFail: true,
        },
      )
      .catch(() => {
        /* worker arrêté en dev */
      });

    for (const salle of sallesInsereesAvecVenueId) {
      if (!salle.tmVenueId) continue;
      await queue
        .add(
          "fetch:salle",
          { type: "fetch:salle", venueId: salle.tmVenueId, userId },
          {
            jobId: `salle__${salle.tmVenueId}`,
            removeOnComplete: true,
            removeOnFail: true,
          },
        )
        .catch(() => {
          /* worker arrêté en dev */
        });
    }

    if (deptsNouveaux.length > 0) {
      await queue
        .add(
          "fetch:region",
          { type: "fetch:region", depts: deptsNouveaux, userId },
          {
            jobId: `region__manuel__${deptsNouveaux.sort().join("_")}`,
            removeOnComplete: true,
            removeOnFail: true,
          },
        )
        .catch(() => {
          /* worker arrêté en dev */
        });
    }

    const nbArtistes = artistesValides.length;
    const nbSalles = sallesValides.length;

    return {
      succes: "import" as const,
      message:
        mode === "ecraser"
          ? `Import terminé : ${nbArtistes} artiste${nbArtistes !== 1 ? "s" : ""} et ${nbSalles} salle${nbSalles !== 1 ? "s" : ""} importés. Mise à jour en cours…`
          : `Fusion terminée. Mise à jour en cours…`,
    };
  },
};
