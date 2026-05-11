import { randomUUID } from "crypto";
import pino from "pino";
import { db } from "$lib/server/db";
import { queue } from "$lib/server/queue";
import { userArtistes, concerts } from "@strapontin/core/schema";
import { normaliserTexte } from "@strapontin/core";
import { eq, and, like, count } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

const logger = pino({ name: "artistes" });
const DEEZER_URL = "https://api.deezer.com/search/artist";

interface SuggestionArtiste {
  nom: string;
  source: "base" | "deezer";
}

async function rechercherDeezer(terme: string): Promise<SuggestionArtiste[]> {
  try {
    const url = `${DEEZER_URL}?q=${encodeURIComponent(terme)}&limit=6`;
    const resp = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!resp.ok) {
      logger.warn({ terme, status: resp.status }, "Deezer recherche artiste erreur HTTP");
      return [];
    }
    const data = (await resp.json()) as {
      data?: { name: string; nb_fan: number }[];
    };
    const resultats = (data.data ?? [])
      .sort((a, b) => b.nb_fan - a.nb_fan);
    const vus = new Set<string>();
    return resultats.flatMap((a) => {
      const norm = normaliserTexte(a.name);
      if (vus.has(norm)) return [];
      vus.add(norm);
      return [{ nom: a.name, source: "deezer" as const }];
    });
  } catch (err) {
    logger.warn({ terme, err }, "Deezer recherche artiste échouée");
    return [];
  }
}

export const load: PageServerLoad = async ({ locals, url }) => {
  const artistes = await db
    .select({
      artisteNormalise: userArtistes.artisteNormalise,
      artisteAffiche: userArtistes.artisteAffiche,
      createdAt: userArtistes.createdAt,
    })
    .from(userArtistes)
    .where(eq(userArtistes.userId, locals.user!.id))
    .orderBy(userArtistes.artisteAffiche);

  const rechercheRaw = url.searchParams.get("q")?.trim() ?? "";
  const recherche = rechercheRaw.slice(0, 100);
  let suggestions: SuggestionArtiste[] = [];

  if (recherche.length >= 2) {
    const rechercheNorm = normaliserTexte(recherche);

    const enBase = await db
      .selectDistinct({ artiste: concerts.artiste })
      .from(concerts)
      .where(like(concerts.artisteNorm, `%${rechercheNorm}%`))
      .orderBy(concerts.artiste)
      .limit(5);

    const nomsSuivisNormalises = new Set(
      artistes.map((a) => a.artisteNormalise),
    );

    suggestions = enBase
      .filter((r) => !nomsSuivisNormalises.has(normaliserTexte(r.artiste)))
      .map((r) => ({ nom: r.artiste, source: "base" as const }));

    // Compléter avec Deezer si < 3 résultats
    if (suggestions.length < 3) {
      const deezerResultats = await rechercherDeezer(recherche);
      const nomsDejaPresents = new Set([
        ...suggestions.map((s) => normaliserTexte(s.nom)),
        ...nomsSuivisNormalises,
      ]);
      for (const r of deezerResultats) {
        if (!nomsDejaPresents.has(normaliserTexte(r.nom))) {
          suggestions.push(r);
          nomsDejaPresents.add(normaliserTexte(r.nom));
        }
      }
    }
  }

  const dejaLeSuivi =
    recherche.length >= 2 &&
    artistes.some((a) => a.artisteNormalise === normaliserTexte(recherche));

  return { artistes, recherche, suggestions, dejaLeSuivi };
};

export const actions: Actions = {
  ajouter: async ({ request, locals }) => {
    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";

    if (!nom) {
      return fail(400, { erreur: "Le nom de l'artiste est obligatoire." });
    }

    if (nom.length > 200) {
      return fail(400, { erreur: "Nom trop long (200 caractères max)." });
    }

    const normalise = normaliserTexte(nom);

    const existants = await db
      .select({ artisteNormalise: userArtistes.artisteNormalise })
      .from(userArtistes)
      .where(
        and(
          eq(userArtistes.userId, locals.user!.id),
          eq(userArtistes.artisteNormalise, normalise),
        ),
      );

    if (existants.length > 0) {
      return fail(409, { erreur: "Cet artiste est déjà dans votre liste." });
    }

    const [{ total }] = await db
      .select({ total: count() })
      .from(userArtistes)
      .where(eq(userArtistes.userId, locals.user!.id));

    if (total >= 500) {
      return fail(400, { erreur: "Limite de 500 artistes atteinte." });
    }

    await db.insert(userArtistes).values({
      userId: locals.user!.id,
      artisteNormalise: normalise,
      artisteAffiche: nom,
    });

    await queue
      .add(
        "fetch:artiste",
        { type: "fetch:artiste", artiste: nom, userId: locals.user!.id, correlationId: randomUUID() },
        {
          attempts: 3,
          backoff: { type: "exponential", delay: 5000 },
          removeOnComplete: { count: 10 },
          removeOnFail: { count: 20 },
        },
      )
      .catch(() => {
        /* le worker est peut-être arrêté en dev */
      });

    return { succes: true };
  },

  supprimer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const artisteNormalise = donnees.get("artisteNormalise")?.toString() ?? "";

    if (!artisteNormalise) {
      return fail(400, { erreur: "Identifiant manquant." });
    }

    await db
      .delete(userArtistes)
      .where(
        and(
          eq(userArtistes.userId, locals.user!.id),
          eq(userArtistes.artisteNormalise, artisteNormalise),
        ),
      );

    return { succes: true };
  },
};
