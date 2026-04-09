import { db } from "$lib/server/db";
import { queue } from "$lib/server/queue";
import { userSalles, concerts } from "@strapontin/core/schema";
import { eq, and, ilike, sql } from "drizzle-orm";
import { fail } from "@sveltejs/kit";
import { TICKETMASTER_API_KEY } from "$env/static/private";
import type { PageServerLoad, Actions } from "./$types";

const TM_VENUES_URL = "https://app.ticketmaster.com/discovery/v2/venues.json";

// Normalise un nom de salle pour la déduplication : retire accents, ponctuation et
// caractères corrompus (??) qui peuvent apparaître selon la source de données.
function normaliserSalleCle(nom: string): string {
  return nom
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]/g, "");
}

interface ResultatSalle {
  salle: string;
  ville: string | null;
  source: "base" | "ticketmaster";
}

async function rechercherSallesTM(terme: string): Promise<ResultatSalle[]> {
  if (!TICKETMASTER_API_KEY) return [];
  try {
    const url =
      `${TM_VENUES_URL}?keyword=${encodeURIComponent(terme)}` +
      `&countryCode=FR&size=6&apikey=${TICKETMASTER_API_KEY}`;
    const resp = await fetch(url, { signal: AbortSignal.timeout(3000) });
    if (!resp.ok) return [];
    const data = (await resp.json()) as {
      _embedded?: {
        venues?: { name: string; city?: { name: string } }[];
      };
    };
    return (data._embedded?.venues ?? []).map((v) => ({
      salle: v.name,
      ville: v.city?.name ?? null,
      source: "ticketmaster" as const,
    }));
  } catch {
    return [];
  }
}

export const load: PageServerLoad = async ({ locals, url }) => {
  const recherche = url.searchParams.get("q")?.trim() ?? "";

  const sallesSuivies = await db
    .select({ salleId: userSalles.salleId })
    .from(userSalles)
    .where(eq(userSalles.userId, locals.user!.id))
    .orderBy(sql`lower(${userSalles.salleId})`);

  const sallesDejaAjoutees = new Set(sallesSuivies.map((s) => s.salleId));
  let resultatsRecherche: ResultatSalle[] = [];

  if (recherche.length >= 2) {
    // Chercher d'abord dans les concerts en base
    const enBase = await db
      .selectDistinct({ salle: concerts.salle, ville: concerts.ville })
      .from(concerts)
      .where(ilike(concerts.salle, `%${recherche}%`))
      .orderBy(concerts.salle)
      .limit(10);

    resultatsRecherche = enBase
      .filter((r) => !sallesDejaAjoutees.has(r.salle))
      .map((r) => ({ ...r, source: "base" as const }));

    // Compléter avec Ticketmaster si < 3 résultats
    if (resultatsRecherche.length < 3) {
      const tmResultats = await rechercherSallesTM(recherche);
      // Utiliser la clé normalisée (sans accents ni ponctuation) pour éviter les doublons
      // même quand la DB contient des données avec encodage corrompu (ex : "D??fense")
      const sallesDejaPresentes = new Set([
        ...resultatsRecherche.map((r) => normaliserSalleCle(r.salle)),
        ...[...sallesDejaAjoutees].map(normaliserSalleCle),
      ]);
      for (const r of tmResultats) {
        if (!sallesDejaPresentes.has(normaliserSalleCle(r.salle))) {
          resultatsRecherche.push(r);
          sallesDejaPresentes.add(normaliserSalleCle(r.salle));
        }
      }
    }
  }

  return {
    sallesSuivies,
    resultatsRecherche,
    recherche,
  };
};

export const actions: Actions = {
  ajouter: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString().trim() ?? "";

    if (!salleId || salleId.length > 200) {
      return fail(400, { erreur: "Identifiant de salle invalide." });
    }

    const existants = await db
      .select({ salleId: userSalles.salleId })
      .from(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    if (existants.length > 0) {
      return fail(409, { erreur: "Cette salle est déjà dans votre liste." });
    }

    await db.insert(userSalles).values({
      userId: locals.user!.id,
      salleId,
    });

    await queue
      .add(
        "recalculer:matches",
        { type: "recalculer:matches", userIds: [locals.user!.id] },
        { removeOnComplete: { count: 10 }, removeOnFail: { count: 20 } },
      )
      .catch(() => {
        /* le worker est peut-être arrêté en dev */
      });

    return { succes: true };
  },

  supprimer: async ({ request, locals }) => {
    const donnees = await request.formData();
    const salleId = donnees.get("salleId")?.toString() ?? "";

    if (!salleId) {
      return fail(400, { erreur: "Identifiant manquant." });
    }

    await db
      .delete(userSalles)
      .where(
        and(
          eq(userSalles.userId, locals.user!.id),
          eq(userSalles.salleId, salleId),
        ),
      );

    return { succes: true };
  },
};
