import { db } from "$lib/server/db";
import { queue } from "$lib/server/queue";
import { userArtistes, concerts } from "@strapontin/core/schema";
import { normaliserTexte } from "@strapontin/core";
import { eq, and, ilike, sql, count } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

const MUSICBRAINZ_URL = "https://musicbrainz.org/ws/2/artist";
const USER_AGENT = "Strapontin/1.0 (strapontin.kwaite.fr)";

interface SuggestionArtiste {
  nom: string;
  pays?: string;
  source: "base" | "musicbrainz";
}

async function rechercherMusicBrainz(
  terme: string,
): Promise<SuggestionArtiste[]> {
  try {
    const url = `${MUSICBRAINZ_URL}?query=${encodeURIComponent(terme)}&fmt=json&limit=6`;
    const resp = await fetch(url, {
      headers: { "User-Agent": USER_AGENT },
      signal: AbortSignal.timeout(3000),
    });
    if (!resp.ok) return [];
    const data = (await resp.json()) as {
      artists?: { name: string; country?: string; score?: number }[];
    };
    return (data.artists ?? [])
      .filter((a) => (a.score ?? 0) >= 60)
      .map((a) => ({
        nom: a.name,
        pays: a.country,
        source: "musicbrainz" as const,
      }));
  } catch {
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
    // Chercher d'abord dans les concerts déjà en base
    const enBase = await db
      .selectDistinct({ artiste: concerts.artiste })
      .from(concerts)
      .where(ilike(concerts.artiste, `%${recherche}%`))
      .orderBy(concerts.artiste)
      .limit(5);

    const nomsSuivisNormalises = new Set(
      artistes.map((a) => a.artisteNormalise),
    );

    suggestions = enBase
      .filter((r) => !nomsSuivisNormalises.has(normaliserTexte(r.artiste)))
      .map((r) => ({ nom: r.artiste, source: "base" as const }));

    // Compléter avec MusicBrainz si < 3 résultats
    if (suggestions.length < 3) {
      const mbResultats = await rechercherMusicBrainz(recherche);
      const nomsDejaPresents = new Set([
        ...suggestions.map((s) => normaliserTexte(s.nom)),
        ...nomsSuivisNormalises,
      ]);
      for (const r of mbResultats) {
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
        { type: "fetch:artiste", artiste: nom, userId: locals.user!.id },
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
